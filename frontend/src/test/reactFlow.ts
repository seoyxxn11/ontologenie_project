// jsdom에는 화면 크기를 재는 기능이 없어서, React Flow(유형 관계도)가 쓰는 것만 흉내 낸다.
// React Flow 공식 문서의 테스트 안내를 따른다.

class ResizeObserverMock {
  private callback: ResizeObserverCallback

  constructor(callback: ResizeObserverCallback) {
    this.callback = callback
  }

  observe(target: Element) {
    const element = target as HTMLElement
    const entry = {
      target,
      contentRect: { width: element.offsetWidth, height: element.offsetHeight },
    }
    this.callback(
      [entry as unknown as ResizeObserverEntry],
      this as unknown as ResizeObserver,
    )
  }

  unobserve() {}

  disconnect() {}
}

class DOMMatrixReadOnlyMock {
  m22: number

  constructor(transform?: string) {
    const scale = transform?.match(/scale\(([1-9.])\)/)?.[1]
    this.m22 = scale !== undefined ? Number(scale) : 1
  }
}

export function mockReactFlow() {
  globalThis.ResizeObserver =
    ResizeObserverMock as unknown as typeof ResizeObserver
  globalThis.DOMMatrixReadOnly =
    DOMMatrixReadOnlyMock as unknown as typeof DOMMatrixReadOnly

  Object.defineProperties(HTMLElement.prototype, {
    offsetHeight: {
      get() {
        return Number.parseFloat(this.style.height) || 1
      },
    },
    offsetWidth: {
      get() {
        return Number.parseFloat(this.style.width) || 1
      },
    },
  })

  Object.defineProperty(SVGElement.prototype, 'getBBox', {
    configurable: true,
    value: () => ({ x: 0, y: 0, width: 0, height: 0 }),
  })
}
