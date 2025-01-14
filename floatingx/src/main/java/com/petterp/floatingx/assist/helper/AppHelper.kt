package com.petterp.floatingx.assist.helper

import android.app.Activity
import com.petterp.floatingx.FloatingX
import com.petterp.floatingx.listener.IFxProxyTagActivityLifecycle
import com.petterp.floatingx.util.FxScopeEnum
import com.petterp.floatingx.util.navigationBarHeight
import com.petterp.floatingx.util.statusBarHeight

/** AppHelper构建器 */
class AppHelper(
    /** 浮窗tag,默认为 [FX_DEFAULT_TAG] */
    var tag: String,
    /** 黑名单list */
    val blackFilterList: MutableList<Class<*>>,
    /** 白名单list */
    val whiteInsertList: MutableList<Class<*>>,
    /** 是否允许插入全部Activity */
    val isAllInstall: Boolean,
    /** 显示悬浮窗的Activity生命周期回调 */
    val fxLifecycleExpand: IFxProxyTagActivityLifecycle?
) : BasisHelper() {

    @JvmSynthetic
    internal fun updateNavigationBar(activity: Activity?) {
        navigationBarHeight = activity?.navigationBarHeight ?: navigationBarHeight
        fxLog?.v("system-> navigationBar-$navigationBarHeight")
    }

    @JvmSynthetic
    internal fun updateStatsBar(activity: Activity?) {
        statsBarHeight = activity?.statusBarHeight ?: statsBarHeight
        fxLog?.v("system-> statusBarHeight-$statsBarHeight")
    }

    class Builder : BasisHelper.Builder<Builder, AppHelper>() {
        private var whiteInsertList: MutableList<Class<*>> = mutableListOf()
        private var blackFilterList: MutableList<Class<*>> = mutableListOf()
        private var fxLifecycleExpand: IFxProxyTagActivityLifecycle? = null
        private var isEnableAllInstall: Boolean = true
        private var tag = FloatingX.FX_DEFAULT_TAG

        /**
         * 设置显示悬浮窗的Activity生命周期回调
         *
         * @param tagActivityLifecycle 生命周期实现类回调
         * @sample
         *     [com.petterp.floatingx.impl.lifecycle.FxTagActivityLifecycleImpl]
         *     空实现,便于直接object:XXX
         */
        fun setTagActivityLifecycle(tagActivityLifecycle: IFxProxyTagActivityLifecycle): Builder {
            this.fxLifecycleExpand = tagActivityLifecycle
            return this
        }

        /**
         * 添加禁止显示悬浮窗的activity
         *
         * @param c 禁止显示的activity
         *
         * [setEnableAllBlackClass(true)] 时,此方法生效
         */
        fun addInstallBlackClass(vararg c: Class<out Activity>): Builder {
            blackFilterList.addAll(c)
            return this
        }

        /**
         * 设置悬浮窗的tag，用于区分不同的悬浮窗
         *
         * 注意：tag 不能为 [""]
         */
        @Throws(IllegalArgumentException::class)
        fun setTag(tag: String): Builder {
            if (tag.isEmpty()) throw IllegalArgumentException("浮窗 tag 不能为 [\"\"],请设置一个合法的tag")
            this.tag = tag
            return this
        }

        /**
         * 允许显示浮窗的activity
         *
         * @param c 允许显示的activity
         *
         * [setEnableAllBlackClass(false)] 时,此方法生效
         */
        fun addInstallWhiteClass(vararg c: Class<out Activity>): Builder {
            whiteInsertList.addAll(c)
            return this
        }

        /**
         * 是否允许给所有浮窗安装悬浮窗
         *
         * @param isEnable 是否允许,默认true
         */
        fun setEnableAllInstall(isEnable: Boolean): Builder {
            isEnableAllInstall = isEnable
            return this
        }

        override fun buildHelper(): AppHelper =
            AppHelper(
                tag,
                blackFilterList,
                whiteInsertList,
                isEnableAllInstall,
                fxLifecycleExpand
            )

        override fun build(): AppHelper {
            val helper = super.build()
            // 有可能用户会使用多个浮窗，这里为了防止日志混乱，将浮窗tag赋值给日志tag
            if (helper.enableDebugLog && helper.fxLogTag.isEmpty()) {
                helper.fxLogTag = tag
            }
            helper.initLog(FxScopeEnum.APP_SCOPE.tag)
            return helper
        }
    }

    companion object {
        @JvmStatic
        fun builder() = Builder()
    }
}
