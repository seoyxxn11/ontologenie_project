import { NavLink, Outlet } from 'react-router'
import './workData.css'

export default function WorkDataLayout() {
  return (
    <>
      <nav aria-label="업무 데이터 보기" className="work-data-tabs">
        <NavLink to="/explorer" end>
          객체 탐색
        </NavLink>
        <NavLink to="/explorer/plans">재배정 계획</NavLink>
      </nav>
      <Outlet />
    </>
  )
}
