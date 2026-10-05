package com.Johnny.wcx.features.items.contacts

import android.graphics.Outline
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.activity.ComponentActivity
import androidx.compose.material3.ListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import de.robv.android.xposed.XC_MethodHook
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexConstructor
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.reflekt.utils.toClass
import org.luckypray.dexkit.DexKitBridge

@Feature(
    name = "圆角头像", categories = ["联系人与群组", "界面美化"],
    description = "自定义微信全局头像渲染的圆角弧度"
)
object RoundAvatars : ClickableFeature(), IResolveDex {

    private const val KEY_ROUND_AVATAR = "round_avatar_radius_factor"
    private const val TAG = "RoundAvatars"

    /**
     * 通讯录顶部硬编码入口（新的朋友 / 群聊 / 标签 / 公众号 / 服务号 / 企业微信联系人）
     * 的图标不走 AvatarDrawable 加载链路，它们是 Fragment 布局里直接 inflate 的内置资源图，
     * 外面包了一层 com.tencent.mm.ui.base.MaskLayout。因此 methodLoadAvatar / ctorAvatarCreate
     * 的 float 参数 hook 影响不到它们，需要额外用 ViewOutlineProvider 在视图层裁圆角。
     */
    private const val MASK_LAYOUT_CLASS = "com.tencent.mm.ui.base.MaskLayout"
    private const val CONTACTS_FRAGMENT_CLASS = "com.tencent.mm.ui.contact.address.MvvmAddressUIFragment"

    /**
     * 所有被我们套过圆角 outline 的 view 的弱引用集合。拖动滑块改 radiusFactor 时，
     * 只要对这些 view 调 invalidateOutline()，avatarOutlineProvider 就会按新值重算圆角，
     * 无需重建页面。
     */
    private val outlinedViews = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<View, Boolean>()
    )

    private val avatarOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val w = view.width.coerceAtLeast(0)
            val h = view.height.coerceAtLeast(0)
            val radius = (minOf(w, h) * radiusFactor).coerceAtLeast(0f)
            outline.setRoundRect(0, 0, w, h, radius)
        }
    }

    private val methodLoadAvatar by dexMethod {
        matcher {
            paramTypes(
                "android.widget.ImageView",
                "java.lang.String",
                "float",
                "boolean"
            )
            usingEqStrings("MicroMsg.AvatarDrawable")
        }
    }
    private val ctorAvatarCreate by dexConstructor {
        matcher {
            usingEqStrings("workerScope", "username")
        }
    }
    private val methodAvatarModify by dexMethod()

    private val radiusFactor: Float
        get() = WePrefs.getFloatOrDef(KEY_ROUND_AVATAR, 0.5f).coerceIn(0.1f, 0.5f)

    override fun onEnable() {
        methodLoadAvatar.hookBefore {
            setFloatArg(2, radiusFactor)
        }

        ctorAvatarCreate.hookBefore {
            setFloatArg(2, radiusFactor)
        }

        if (!methodAvatarModify.isPlaceholder) {
            methodAvatarModify.hookBefore {
                setFloatArg(3, radiusFactor)
            }
        }

        installContactsHeaderCornerHook()

        notifyCustomContactAvatarChanged()
    }

    override fun onDisable() {
        // 关闭功能时把视图层裁剪恢复成默认，顶部入口图标回到微信原本的样子。
        outlinedViews.forEach {
            it.outlineProvider = ViewOutlineProvider.BACKGROUND
            it.clipToOutline = false
        }
        outlinedViews.clear()
        notifyCustomContactAvatarChanged()
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        val modifyMethods = dexKit.findMethod {
            matcher {
                usingEqStrings("workerScope", "username")
            }
        }.filter { it.methodName != "<init>" }

        val modifyMethod = modifyMethods.singleOrNull()
        if (modifyMethod == null) {
            methodAvatarModify.setPlaceholderDescriptor()
        } else {
            methodAvatarModify.setDescriptor(modifyMethod)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var value by remember { mutableFloatStateOf(radiusFactor) }

            AlertDialogContent(
                title = { Text("圆角头像") },
                text = {
                    ListItem(
                        supportingContent = {
                            Slider(
                                value = value,
                                onValueChange = { value = it.coerceIn(0.1f, 0.5f) },
                                valueRange = 0.1f..0.5f,
                                steps = 39
                            )
                        },
                        headlineContent = { Text("圆角弧度: %.2f".format(value)) },
                    )
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        WePrefs.putFloat(KEY_ROUND_AVATAR, value.coerceIn(0.1f, 0.5f))
                        notifyCustomContactAvatarChanged()
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }

    private fun XC_MethodHook.MethodHookParam.setFloatArg(index: Int, value: Float) {
        if (index in args.indices) args[index] = value
    }

    /**
     * Hook 通讯录 Fragment 的 view 创建完成，从 root view 递归给所有 MaskLayout 套圆角 outline。
     *
     * 微信通讯录顶部入口（新朋友/群聊/标签/公众号/服务号/企业微信）跟好友列表
     * 都在同一个 RecyclerView 里——不能跳过 RecyclerView 子树，否则顶部入口的
     * MaskLayout 全被漏掉。好友头像已经走 AvatarDrawable 渲染成圆角 bitmap，
     * 再套一层 clipToOutline（radius 一致）视觉上不会变差。
     *
     * 同时 hook 两个生命周期方法兜底：
     * - onViewCreated(View, Bundle)：androidx Fragment 标准方法
     * - onCreateView 返回 View：微信自己的 Fragment 基类可能用不同方法名
     * 哪个先触发就用哪个，幂等（WeakHashMap 跟踪已处理 view）。
     */
    private fun installContactsHeaderCornerHook() {
        val clazz = CONTACTS_FRAGMENT_CLASS.toClass()

        // 主路径：onViewCreated(View, Bundle)
        runCatching {
            clazz.reflekt()
                .firstMethod { parameters(View::class.java, Bundle::class.java) }
                .hookAfter {
                    val root = args.getOrNull(0) as? ViewGroup ?: return@hookAfter
                    applyRoundToMaskLayouts(root)
                }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook onViewCreated", it)
        }

        // 兜底：onCreateView 返回值是 root view
        runCatching {
            clazz.methods
                .filter { it.returnType == View::class.java && it.parameterTypes.size >= 2 }
                .forEach { m ->
                    m.hookAfter {
                        val root = result as? ViewGroup ?: return@hookAfter
                        applyRoundToMaskLayouts(root)
                    }
                }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook onCreateView", it)
        }
    }

    private fun applyRoundToMaskLayouts(root: ViewGroup) {
        root.post { traverseAndRound(root) }
        root.postDelayed({ traverseAndRound(root) }, 200L)
        root.postDelayed({ traverseAndRound(root) }, 500L)
    }

    private fun traverseAndRound(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.javaClass.name == MASK_LAYOUT_CLASS) {
                child.outlineProvider = avatarOutlineProvider
                child.clipToOutline = true
                outlinedViews.add(child)
            }
            if (child is ViewGroup) {
                traverseAndRound(child)
            }
        }
    }

    private fun notifyCustomContactAvatarChanged() {
        // 拖动滑块后，已显示的顶部入口图标按新 radiusFactor 重算圆角，无需重进页面。
        outlinedViews.forEach { it.invalidateOutline() }
        runCatching {
            if (CustomLocalFriendAvatars.isActive) {
                CustomLocalFriendAvatars.onRoundAvatarConfigChanged()
            }
        }
    }
}
