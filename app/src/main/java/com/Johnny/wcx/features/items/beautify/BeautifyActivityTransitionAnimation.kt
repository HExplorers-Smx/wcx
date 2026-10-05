package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger

@Feature(name = "美化活动过渡动画", categories = ["界面美化"], description = "将部分活动过渡动画替换为从点击位置放大的共享元素风格过渡")
object BeautifyActivityTransitionAnimation : SwitchFeature() {

    private const val TAG = "BeautifyActivityTransitionAnimation"

    /** 上次点击的位置/尺寸，用于共享元素过渡起点 */
    private var anchorWidth = 0
    private var anchorHeight = 0
    private var anchorX = 0f
    private var anchorY = 0f

    /** 上次 performClick 的时间戳，用于判断本次 onPostCreate 是否由该点击触发 */
    private var lastClickTime = 0L

    /** 标记是否有待消费的过渡（onPostCreate 消费一次后清除，避免重复播） */
    private var pendingTransition = false

    /** 点击后多久内创建的 Activity 才算"点进来的"，超过就不播动画 */
    private const val TRANSITION_WINDOW_MS = 800L

    override fun onEnable() {
        // 记录任意 View 被点击的位置（作为共享元素起点）
        View::class.reflekt()
            .firstMethod { name = "performClick" }
            .hookBefore {
                val view = thisObject as? View ?: return@hookBefore
                anchorWidth = view.width
                anchorHeight = view.height
                val location = intArrayOf(0, 0)
                view.getLocationOnScreen(location)
                anchorX = location[0].toFloat()
                anchorY = location[1].toFloat()
                lastClickTime = System.currentTimeMillis()
                pendingTransition = true
            }

        // 禁用微信自带的 Activity 过渡动画（slide/zoom）
        Activity::class.reflekt()
            .firstMethod {
                name = "overridePendingTransition"
                parameterCount = 3
            }
            .hookBefore {
                result = null
            }

        // 在新 Activity 的 decorView 上叠加一个从点击位置放大的 mask，模拟共享元素过渡
        Activity::class.reflekt()
            .firstMethod { name = "onPostCreate" }
            .hookBefore {
                val activity = thisObject as Activity
                // 只消费一次；不在时间窗口内的（配置变更、桌面启动等）不播
                if (!pendingTransition) return@hookBefore
                if (System.currentTimeMillis() - lastClickTime > TRANSITION_WINDOW_MS) {
                    pendingTransition = false
                    return@hookBefore
                }
                if (anchorWidth <= 0 || anchorHeight <= 0) {
                    pendingTransition = false
                    return@hookBefore
                }
                pendingTransition = false

                val decorView = activity.window.decorView as? ViewGroup ?: return@hookBefore
                decorView.post {
                    val mask = View(activity)
                    // 取主题背景色，避免浅色模式下白色 mask 突兀
                    val typedValue = android.util.TypedValue()
                    activity.theme.resolveAttribute(android.R.attr.windowBackground, typedValue, true)
                    mask.setBackgroundColor(typedValue.data.takeIf { it != 0 } ?: Color.WHITE)
                    mask.elevation = 1000f

                    val lp = FrameLayout.LayoutParams(anchorWidth, anchorHeight)
                    mask.layoutParams = lp
                    decorView.addView(mask)

                    mask.x = anchorX
                    mask.y = anchorY
                    mask.pivotX = 0f
                    mask.pivotY = 0f

                    val screenW = decorView.width.toFloat().takeIf { it > 0 } ?: return@post
                    val screenH = decorView.height.toFloat().takeIf { it > 0 } ?: return@post

                    mask.animate()
                        .x(0f)
                        .y(0f)
                        .scaleX(screenW / anchorWidth)
                        .scaleY(screenH / anchorHeight)
                        .setDuration(450)
                        .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
                        .withEndAction {
                            if (mask.parent is ViewGroup) {
                                (mask.parent as ViewGroup).removeView(mask)
                            }
                        }
                        .start()
                }
            }
    }

    override fun onDisable() {
        // 清理状态，关闭功能后不再播动画
        anchorWidth = 0
        anchorHeight = 0
        anchorX = 0f
        anchorY = 0f
        lastClickTime = 0L
        pendingTransition = false
    }
}
