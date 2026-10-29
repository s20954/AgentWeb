import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

/**
 * 门户路由表。
 *
 * 结构对齐规划书「用户端门户 + AI 对话端」：
 * - `/` 为门户布局（顶部导航），子路由为各功能页；
 * - `/agent` 为 AI 对话预约页（独立全屏，保留原有思考链体验）；
 * - `/404` 兜底。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('@/views/portal/PortalLayout.vue'),
    redirect: '/home',
    children: [
      { path: 'home', name: 'home', component: () => import('@/views/portal/HomeView.vue'), meta: { title: '首页' } },
      { path: 'homestay', name: 'homestay', component: () => import('@/views/portal/HomestayView.vue'), meta: { title: '民宿' } },
      { path: 'homestay/:id', name: 'homestay-detail', component: () => import('@/views/portal/HomestayDetailView.vue'), meta: { title: '民宿详情' } },
      { path: 'spot', name: 'spot', component: () => import('@/views/portal/SpotView.vue'), meta: { title: '景点' } },
      { path: 'spot/:id', name: 'spot-detail', component: () => import('@/views/portal/SpotDetailView.vue'), meta: { title: '景点详情' } },
      { path: 'order', name: 'order', component: () => import('@/views/portal/OrderView.vue'), meta: { title: '我的订单' } },
      { path: 'profile', name: 'profile', component: () => import('@/views/portal/ProfileView.vue'), meta: { title: '个人中心' } },
    ],
  },
  {
    path: '/agent',
    name: 'agent',
    component: () => import('@/views/ChatView.vue'),
    meta: { title: 'AI 助手' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/portal/NotFoundView.vue'),
    meta: { title: '页面不存在' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  },
})

// 路由标题
router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · 民宿预约` : '民宿预约'
})

export default router
