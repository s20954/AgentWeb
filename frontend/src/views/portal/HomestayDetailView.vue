<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { findHomestay, getRoomTypes, type Homestay, type RoomType } from '@/mock/data'
import { useUserDataStore } from '@/stores/userData'
import ReviewSection from '@/components/ReviewSection.vue'

const route = useRoute()
const router = useRouter()
const userData = useUserDataStore()

const homestay = computed<Homestay | undefined>(() => findHomestay(String(route.params.id)))

// 房型列表
const roomTypes = computed<RoomType[]>(() => (homestay.value ? getRoomTypes(homestay.value) : []))

// 当前选中房型（默认第一个）
const selectedRoomType = ref<RoomType | null>(null)
const selectedRoom = computed<RoomType | null>(() => selectedRoomType.value ?? roomTypes.value[0] ?? null)

function selectRoom(rt: RoomType): void {
  selectedRoomType.value = rt
}

// 预订表单
const checkIn = ref('')
const checkOut = ref('')
const contactPhone = ref('')
const guestNum = ref(1)
const submitted = ref(false)

// 图片画廊：封面 + 补充图
const gallery = computed<string[]>(() => {
  if (!homestay.value) return []
  const base = homestay.value.gallery && homestay.value.gallery.length
    ? homestay.value.gallery
    : [
        'photo-1600585154340-be6161a56a0c',
        'photo-1566073771259-6a8506099945',
        'photo-1582719478250-c89cae4dc85b',
        'photo-1571896349842-33c89424de2d',
        'photo-1600607687939-ce8a6c25118c',
      ]
  const list = base.map((seed) =>
    seed.startsWith('http') ? seed : `https://images.unsplash.com/${seed}?w=1200&q=80&auto=format&fit=crop`,
  )
  if (homestay.value.image) list[0] = homestay.value.image
  return list
})

// 晚数（自动计算）
const nights = computed(() => {
  if (!checkIn.value || !checkOut.value) return 0
  const a = new Date(checkIn.value).getTime()
  const b = new Date(checkOut.value).getTime()
  if (isNaN(a) || isNaN(b) || b <= a) return 0
  return Math.round((b - a) / (1000 * 60 * 60 * 24))
})

// 单价 = 选中房型价格
const unitPrice = computed(() => selectedRoom.value?.price ?? homestay.value?.price ?? 0)

// 总价
const totalPrice = computed(() => nights.value * unitPrice.value)

// 最大入住人数 = 选中房型可住人数
const maxGuests = computed(() => selectedRoom.value?.maxGuests ?? homestay.value?.maxGuests ?? 10)

function submit(): void {
  if (!homestay.value || !checkIn.value || !checkOut.value || !contactPhone.value || nights.value <= 0) return
  if (guestNum.value > maxGuests.value) guestNum.value = maxGuests.value
  submitted.value = true
  userData.addOrder({
    orderId: 'OR' + Date.now(),
    homestayId: homestay.value.homestayId,
    homestayTitle: homestay.value.title,
    homestayImage: homestay.value.image,
    checkInDate: checkIn.value,
    checkOutDate: checkOut.value,
    nights: nights.value,
    totalPrice: totalPrice.value,
    contactPhone: contactPhone.value,
    status: '待支付',
    roomType: selectedRoom.value?.name,
  })
  setTimeout(() => router.push('/order'), 800)
}

function goBack(): void {
  router.back()
}

function clampGuestNum(): void {
  const n = Number(guestNum.value)
  if (isNaN(n) || n < 1) guestNum.value = 1
  else if (n > maxGuests.value) guestNum.value = maxGuests.value
}

// 切换房型时重置入住人数上限
function onSelectRoom(rt: RoomType): void {
  selectRoom(rt)
  if (guestNum.value > rt.maxGuests) guestNum.value = rt.maxGuests
}

// 评价：读取 store 中的评价（持久化）
const reviews = computed(() => (homestay.value ? userData.homestayReviewList(homestay.value.homestayId) : []))

function submitReview(payload: { user: string; score: number; content: string }): void {
  if (!homestay.value) return
  userData.addHomestayReview(homestay.value.homestayId, {
    id: 'rv-' + Date.now(),
    user: payload.user,
    score: payload.score,
    content: payload.content,
    date: new Date().toISOString().slice(0, 10),
  })
}

// 从订单页「评价」跳转来时（?review=1），无需额外处理——详情页评价区始终可见，
// 用户点击「写评价」即可展开表单。
</script>

<template>
  <div v-if="homestay">
    <!-- 返回 -->
    <button type="button" class="mb-4 inline-flex items-center gap-1 text-sm text-stone-500 hover:text-emerald-700 transition-colors" @click="goBack">
      <svg viewBox="0 0 24 24" fill="none" class="w-4 h-4" stroke="currentColor" stroke-width="1.5">
        <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 19.5L8.25 12l7.5-7.5" />
      </svg>
      返回
    </button>

    <!-- 图片画廊：大图 + 2×2 小图 -->
    <div class="grid grid-cols-4 grid-rows-2 gap-2 h-80 sm:h-96 mb-6">
      <div class="col-span-2 row-span-2 rounded-l-2xl overflow-hidden">
        <img :src="gallery[0]" :alt="homestay.title" class="w-full h-full object-cover" />
      </div>
      <div v-for="(img, i) in gallery.slice(1, 5)" :key="i" class="overflow-hidden" :class="i === 3 ? 'rounded-br-2xl' : ''">
        <img :src="img" :alt="homestay.title" class="w-full h-full object-cover hover:scale-105 transition-transform duration-300" />
      </div>
      <div v-if="gallery.length < 4" v-for="i in 4 - gallery.length" :key="'ph' + i" class="bg-stone-100"></div>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
      <!-- 左：详情 -->
      <div class="lg:col-span-2">
        <div class="bg-white rounded-2xl p-6 shadow-sm">
          <div class="flex items-start justify-between gap-4">
            <h1 class="text-2xl font-bold text-stone-800">{{ homestay.title }}</h1>
            <div class="text-emerald-700 text-2xl font-bold shrink-0">¥{{ homestay.price }}<span class="text-sm font-normal text-stone-400">/ 晚起</span></div>
          </div>
          <div class="mt-2 flex items-center gap-2 text-sm text-stone-500">
            <span class="flex items-center gap-1 text-amber-500 font-semibold">
              <svg viewBox="0 0 24 24" fill="currentColor" class="w-4 h-4"><path d="M11.48 3.5c.31-.64 1.27-.64 1.58 0l1.79 3.63 4 .58c.71.1.99.97.48 1.47l-2.89 2.82.68 3.98c.12.7-.62 1.24-1.25.9L12 14.62l-3.57 1.88c-.63.33-1.37-.2-1.25-.9l.68-3.98-2.89-2.82c-.51-.5-.23-1.37.48-1.47l4-.58 1.79-3.63z" /></svg>
              {{ homestay.score }}
            </span>
            <span>·</span>
            <span>{{ homestay.area }}</span>
            <span>·</span>
            <span>{{ homestay.address }}</span>
          </div>

          <!-- 服务亮点 -->
          <div class="mt-4 flex flex-wrap gap-2">
            <span v-for="(t, i) in homestay.tags" :key="i" class="px-2.5 py-1 rounded-md bg-emerald-50 text-emerald-700 text-xs font-medium">{{ t }}</span>
          </div>

          <div class="mt-6">
            <h3 class="text-lg font-semibold text-stone-800 mb-2">民宿介绍</h3>
            <p class="text-stone-600 leading-relaxed">{{ homestay.description }}</p>
          </div>
        </div>

        <!-- 房型选择 -->
        <div class="mt-6 bg-white rounded-2xl p-6 shadow-sm">
          <h3 class="text-lg font-semibold text-stone-800 mb-4">选择房型</h3>
          <div class="space-y-3">
            <div
              v-for="rt in roomTypes"
              :key="rt.name"
              class="flex items-center justify-between gap-4 p-4 rounded-xl border-2 cursor-pointer transition-all"
              :class="selectedRoom?.name === rt.name ? 'border-emerald-600 bg-emerald-50/50' : 'border-stone-200 hover:border-emerald-300'"
              @click="onSelectRoom(rt)"
            >
              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-2">
                  <span class="font-semibold text-stone-800">{{ rt.name }}</span>
                  <span v-if="rt.feature" class="text-xs text-stone-400">{{ rt.feature }}</span>
                </div>
                <div class="mt-1 text-sm text-stone-500">
                  {{ rt.bedType }} · {{ rt.area }}㎡ · 可住 {{ rt.maxGuests }} 人
                </div>
              </div>
              <div class="text-right shrink-0">
                <div class="text-emerald-700 text-lg font-bold">¥{{ rt.price }}<span class="text-xs font-normal text-stone-400">/晚</span></div>
                <div v-if="selectedRoom?.name === rt.name" class="mt-1 text-xs text-emerald-600 font-medium">已选</div>
              </div>
            </div>
          </div>
        </div>

        <!-- 评价 -->
        <div class="mt-6 bg-white rounded-2xl p-6 shadow-sm">
          <ReviewSection :reviews="reviews" title="住客评价" :on-submit="submitReview" />
        </div>
      </div>

      <!-- 右：预订卡片 -->
      <div class="lg:col-span-1">
        <div class="bg-white rounded-2xl p-6 shadow-sm sticky top-20">
          <div class="flex items-baseline gap-1 mb-4">
            <span class="text-2xl font-bold text-emerald-700">¥{{ unitPrice }}</span>
            <span class="text-sm text-stone-400">/ 晚</span>
            <span v-if="selectedRoom" class="ml-auto text-xs text-stone-400">{{ selectedRoom.name }}</span>
          </div>
          <div class="space-y-4">
            <div>
              <label class="block text-sm text-stone-600 mb-1.5">入住日期</label>
              <input v-model="checkIn" type="date" class="w-full h-11 px-3 rounded-xl border border-stone-200 text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500" />
            </div>
            <div>
              <label class="block text-sm text-stone-600 mb-1.5">退房日期</label>
              <input v-model="checkOut" type="date" class="w-full h-11 px-3 rounded-xl border border-stone-200 text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500" />
            </div>
            <div>
              <label class="block text-sm text-stone-600 mb-1.5">入住人数 <span class="text-stone-400">（最多 {{ maxGuests }} 人）</span></label>
              <input
                v-model.number="guestNum"
                type="number"
                :min="1"
                :max="maxGuests"
                @blur="clampGuestNum"
                class="w-full h-11 px-3 rounded-xl border border-stone-200 text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500"
              />
            </div>
            <div>
              <label class="block text-sm text-stone-600 mb-1.5">联系电话</label>
              <input v-model="contactPhone" type="tel" placeholder="请输入手机号" class="w-full h-11 px-3 rounded-xl border border-stone-200 text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500" />
            </div>

            <!-- 费用明细 -->
            <div class="bg-stone-50 rounded-xl p-4 space-y-2 text-sm">
              <div class="flex justify-between text-stone-600">
                <span>¥{{ unitPrice }} × {{ nights }} 晚</span>
                <span>¥{{ totalPrice }}</span>
              </div>
              <div class="flex justify-between font-semibold text-stone-800 pt-2 border-t border-stone-200">
                <span>总价</span>
                <span class="text-emerald-700">¥{{ totalPrice }}</span>
              </div>
            </div>

            <button
              type="button"
              class="w-full h-12 rounded-xl bg-emerald-700 text-white font-semibold hover:bg-emerald-800 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
              :disabled="nights <= 0 || !contactPhone || submitted"
              @click="submit"
            >
              {{ submitted ? '正在下单…' : '立即预订' }}
            </button>
            <p class="text-center text-xs text-emerald-600">入住当天 18:00 前可免费取消</p>
            <p v-if="nights <= 0 && (checkIn || checkOut)" class="text-xs text-amber-600">请选择有效的入住/退房日期</p>
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- 未找到 -->
  <div v-else class="py-24 text-center text-stone-400">
    <p class="text-lg mb-2">未找到该民宿</p>
    <button type="button" class="text-emerald-700 hover:underline" @click="router.push('/homestay')">返回民宿列表</button>
  </div>
</template>
