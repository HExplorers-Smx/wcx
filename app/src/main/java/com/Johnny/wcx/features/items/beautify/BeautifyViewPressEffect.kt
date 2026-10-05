package com.Johnny.wcx.features.items.beautify

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(name = "美化组件按下效果", categories = ["界面美化"], description = "将可点击 View 的背景包装为 RippleDrawable，按下时显示水波纹")
object BeautifyViewPressEffect : SwitchFeature() {

    /** 按压水波纹颜色（半透明黑） */
    private val rippleColor = ColorStateList.valueOf(0x1F000000)

    override fun onEnable() {
        // setBackgroundDrawable 是所有背景设置的最终入口：
        // setBackgroundResource / setBackgroundColor / setBackground 都会走到这里
        View::class.reflekt()
            .firstMethod {
                name = "setBackgroundDrawable"
                parameters(Drawable::class)
            }
            .hookBefore {
                val view = thisObject as? View ?: return@hookBefore
                // 跳过框架自己的 View（TextView/ImageView 子类也包，避免漏掉微信自定义控件）
                if (view.javaClass.name.startsWith("android.widget.")) return@hookBefore
                if (view.javaClass.name.startsWith("android.view.")) return@hookBefore

                val original = args[0] as? Drawable ?: return@hookBefore
                // 已经是 RippleDrawable 的不重复包
                if (original is RippleDrawable) return@hookBefore
                // 不可点击的 View 不需要水波纹
                if (!view.isClickable) return@hookBefore

                // 把原 drawable 作为 content layer，包一层 Ripple
                args[0] = RippleDrawable(rippleColor, original, null)
            }
    }

    override fun onDisable() {
        // hook 会被 BaseFeature 自动 unhook；已被替换为 RippleDrawable 的 View
        // 需要重启微信才能恢复原背景（运行时无法可靠还原原 drawable）
    }
}
