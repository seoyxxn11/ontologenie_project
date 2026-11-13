import { expect, test } from 'vitest'
import { focusTypeGraph } from '../features/explorer/modelGraphFocus'
import { createDenseExplorerDemo } from './denseExplorerDemo'

test('대량 예시는 27개 유형·40개 관계·3396개 객체이며 실제 연결 건수와 유형 관계도가 일치한다', () => {
  const { graph, objects, links } = createDenseExplorerDemo()
  expect(graph.nodes).toHaveLength(27)
  expect(graph.edges).toHaveLength(40)
  expect(objects).toHaveLength(3396)
  const types = new Set(graph.nodes.map((node) => node.id))
  const objectById = new Map(objects.map((object) => [object.id, object]))
  expect(objectById.size).toBe(objects.length)
  expect(new Set(links.map((link) => link.id)).size).toBe(links.length)
  for (const edge of graph.edges) {
    expect(types.has(edge.sourceTypeId)).toBe(true)
    expect(types.has(edge.targetTypeId)).toBe(true)
    const related = links.filter((link) => link.linkTypeId === edge.id)
    expect(related.length).toBe(edge.activeLinkCount)
    expect(related.length).toBe(edge.relationCount)
    for (const link of related) {
      expect(objectById.get(link.sourceObjectId)?.objectTypeId).toBe(
        edge.sourceTypeId,
      )
      expect(objectById.get(link.targetObjectId)?.objectTypeId).toBe(
        edge.targetTypeId,
      )
    }
  }
})

test('들어옴·나감이 함께 많은 유형, 양방향 유형, 연결 없는 유형과 연결 0건을 포함한다', () => {
  const { graph } = createDenseExplorerDemo()
  const workOrder = graph.nodes.find((node) => node.name === '작업지시')!
  const focused = focusTypeGraph(graph, workOrder.id)
  expect(focused.nodes).toHaveLength(9)
  expect(
    focused.edges.filter((edge) => edge.targetTypeId === workOrder.id),
  ).toHaveLength(5)
  expect(
    focused.edges.filter((edge) => edge.sourceTypeId === workOrder.id),
  ).toHaveLength(3)
  expect(graph.edges.some((edge) => edge.activeLinkCount === 0)).toBe(true)
  const employee = graph.nodes.find((node) => node.name === '직원')!
  const department = graph.nodes.find((node) => node.name === '부서')!
  const directions = focusTypeGraph(graph, employee.id).edges.map(
    (edge) => `${edge.sourceTypeId}-${edge.targetTypeId}`,
  )
  expect(directions).toContain(`${employee.id}-${department.id}`)
  expect(directions).toContain(`${department.id}-${employee.id}`)
  const isolated = graph.nodes.find((node) => node.name === '고정자산')!
  expect(focusTypeGraph(graph, isolated.id).edges).toHaveLength(0)
})
