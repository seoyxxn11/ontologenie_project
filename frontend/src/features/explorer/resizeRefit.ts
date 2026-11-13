type Size = { width: number; height: number }

// 그림 칸 크기가 바뀔 때 다시 맞출지 정한다.
// 마지막으로 맞춘 크기와 비교하므로, 창을 조금씩 끌어 크게 바꿔도 합친 변화가 기준을 넘으면 다시 맞춘다.
// 스크롤바가 생기고 없어지는 정도의 작은 변화로는 맞추지 않아, 사용자가 직접 확대한 화면이 유지된다.
export function createRefitCheck(initial: Size, threshold: number) {
  let fitted = initial
  return (size: Size) => {
    const changed =
      Math.abs(size.width - fitted.width) >= threshold ||
      Math.abs(size.height - fitted.height) >= threshold
    if (changed) fitted = size
    return changed
  }
}
