/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

// Web Speech API 类型声明：浏览器（尤其 Chrome）通过 webkitSpeechRecognition 暴露，
// 标准 TS DOM lib 未内置完整类型，这里用 any 做守卫，避免组件内报类型错误。
declare global {
  interface Window {
    SpeechRecognition?: any
    webkitSpeechRecognition?: any
  }
}

export {}
