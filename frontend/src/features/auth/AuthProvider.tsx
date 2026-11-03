import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, type ReactNode } from 'react'
import { ApiError } from '../../api/errors'
import { fetchMyAccount, login, logout } from './api'
import { AuthContext } from './auth-context'
import type { Account, LoginInput } from './types'

const accountKey = ['auth', 'me'] as const

function AuthProvider({ children }: { children: ReactNode }) {
  const client = useQueryClient()
  const operation = useRef(false)
  const accountQuery = useQuery({
    queryKey: accountKey,
    queryFn: async ({ signal }) => {
      const next = await fetchMyAccount(signal)
      const previous = client.getQueryData<Account | null>(accountKey)
      if (
        previous &&
        (!next ||
          previous.id !== next.id ||
          previous.roleCode !== next.roleCode ||
          previous.status !== next.status ||
          previous.mustChangePassword !== next.mustChangePassword)
      ) {
        await client.cancelQueries({
          predicate: (query) => query.queryKey[0] !== 'auth',
        })
        client.removeQueries({
          predicate: (query) => query.queryKey[0] !== 'auth',
        })
        client.getMutationCache().clear()
      }
      return next
    },
    retry: false,
  })

  useEffect(() => {
    function expire() {
      void client.cancelQueries()
      client.removeQueries({
        predicate: (query) => query.queryKey[0] !== 'auth',
      })
      client.getMutationCache().clear()
      client.setQueryData(accountKey, null)
    }
    const unsubscribeQueries = client.getQueryCache().subscribe((event) => {
      if (
        event.type !== 'updated' ||
        event.action.type !== 'error' ||
        event.query.queryKey[0] === 'auth'
      )
        return
      if (client.getQueryCache().get(event.query.queryHash) !== event.query)
        return
      const error = event.query.state.error
      if (error instanceof ApiError && error.status === 401) {
        expire()
      }
    })
    const unsubscribeMutations = client
      .getMutationCache()
      .subscribe((event) => {
        if (event.type !== 'updated' || event.action.type !== 'error') return
        if (!client.getMutationCache().getAll().includes(event.mutation)) return
        const error = event.mutation.state.error
        if (error instanceof ApiError && error.status === 401) expire()
      })
    return () => {
      unsubscribeQueries()
      unsubscribeMutations()
    }
  }, [client])

  useEffect(() => {
    if (accountQuery.data === null) {
      void client.cancelQueries({
        predicate: (query) => query.queryKey[0] !== 'auth',
      })
      client.removeQueries({
        predicate: (query) => query.queryKey[0] !== 'auth',
      })
      client.getMutationCache().clear()
    }
  }, [accountQuery.data, client])

  async function signIn(input: LoginInput) {
    if (operation.current) return
    operation.current = true
    try {
      const account = await login(input)
      await client.cancelQueries()
      client.removeQueries({
        predicate: (query) => query.queryKey[0] !== 'auth',
      })
      client.getMutationCache().clear()
      client.setQueryData(accountKey, account)
    } finally {
      operation.current = false
    }
  }

  async function signOut() {
    if (operation.current) return
    operation.current = true
    try {
      try {
        await logout()
      } catch (error) {
        if (!(error instanceof ApiError && error.status === 401)) throw error
      }
      await client.cancelQueries()
      client.removeQueries({
        predicate: (query) => query.queryKey[0] !== 'auth',
      })
      client.getMutationCache().clear()
      client.setQueryData(accountKey, null)
    } finally {
      operation.current = false
    }
  }

  return (
    <AuthContext.Provider
      value={{
        account: accountQuery.data,
        pending: accountQuery.isPending,
        error: accountQuery.error,
        refresh: accountQuery.refetch,
        signIn,
        signOut,
      }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export default AuthProvider
