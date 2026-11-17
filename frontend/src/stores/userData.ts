import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  orderList as seedOrders,
  defaultHomestayReviews,
  defaultSpotReviews,
  type Order,
  type Review,
} from '@/mock/data'

/**
 * 用户数据 store（订单 + 评价），统一用 localStorage 持久化。
 *
 * 背景（2026-11-17 修复）：此前订单/评价散落在各页面的模块级常量数组里，
 * 只改内存、不落盘，导致「点支付后切页面再回来又变回待支付」「订单页不同步」。
 * 这里收敛为单一数据源，所有写操作（支付/取消/删除/下单/评价）都同步到 localStorage，
 * 切页面、刷新均不丢失。
 */

const ORDERS_KEY = 'minsu-orders'
const HOMESTAY_REVIEWS_KEY = 'minsu-homestay-reviews'
const SPOT_REVIEWS_KEY = 'minsu-spot-reviews'

function load<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return fallback
    return JSON.parse(raw) as T
  } catch {
    return fallback
  }
}

function save(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // 忽略写入失败（如隐私模式），功能降级为内存态
  }
}

export const useUserDataStore = defineStore('userData', () => {
  // ---- 订单 ----
  const orders = ref<Order[]>(load(ORDERS_KEY, seedOrders))

  function persistOrders(): void {
    save(ORDERS_KEY, orders.value)
  }

  /** 新增订单（下单）。 */
  function addOrder(order: Order): void {
    orders.value.unshift(order)
    persistOrders()
  }

  /** 支付：待支付 → 已支付（幂等）。 */
  function payOrder(orderId: string): void {
    const o = orders.value.find((x) => x.orderId === orderId)
    if (o && o.status === '待支付') {
      o.status = '已支付'
      persistOrders()
    }
  }

  /** 取消：待支付 → 已取消（幂等）。 */
  function cancelOrder(orderId: string): void {
    const o = orders.value.find((x) => x.orderId === orderId)
    if (o && o.status === '待支付') {
      o.status = '已取消'
      persistOrders()
    }
  }

  /** 删除订单（从列表移除）。 */
  function deleteOrder(orderId: string): void {
    orders.value = orders.value.filter((x) => x.orderId !== orderId)
    persistOrders()
  }

  /** 动态修改订单的联系电话（幂等，写 localStorage）。 */
  function updateOrderPhone(orderId: string, phone: string): void {
    const o = orders.value.find((x) => x.orderId === orderId)
    if (o) {
      o.contactPhone = phone
      persistOrders()
    }
  }

  /** 完成订单（已支付 → 已完成，用于演示评价入口）。 */
  function completeOrder(orderId: string): void {
    const o = orders.value.find((x) => x.orderId === orderId)
    if (o && o.status === '已支付') {
      o.status = '已完成'
      persistOrders()
    }
  }

  // ---- 评价 ----
  /** 评价按目标 id 分桶存储：homestayReviews[homestayId] = Review[] */
  const homestayReviews = ref<Record<string, Review[]>>(load(HOMESTAY_REVIEWS_KEY, {}))
  const spotReviews = ref<Record<string, Review[]>>(load(SPOT_REVIEWS_KEY, {}))

  function homestayReviewList(homestayId: string): Review[] {
    return homestayReviews.value[homestayId] ?? defaultHomestayReviews
  }

  function spotReviewList(spotId: string): Review[] {
    return spotReviews.value[spotId] ?? defaultSpotReviews
  }

  function addHomestayReview(homestayId: string, review: Review): void {
    const list = homestayReviews.value[homestayId]
      ? [...homestayReviews.value[homestayId], review]
      : [...defaultHomestayReviews, review]
    homestayReviews.value[homestayId] = list
    save(HOMESTAY_REVIEWS_KEY, homestayReviews.value)
  }

  function addSpotReview(spotId: string, review: Review): void {
    const list = spotReviews.value[spotId]
      ? [...spotReviews.value[spotId], review]
      : [...defaultSpotReviews, review]
    spotReviews.value[spotId] = list
    save(SPOT_REVIEWS_KEY, spotReviews.value)
  }

  return {
    orders,
    addOrder,
    payOrder,
    cancelOrder,
    deleteOrder,
    updateOrderPhone,
    completeOrder,
    homestayReviewList,
    spotReviewList,
    addHomestayReview,
    addSpotReview,
  }
})
