package com.petterp.floatingx.view

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import com.petterp.floatingx.assist.FxGravity
import com.petterp.floatingx.assist.helper.BasisHelper
import com.petterp.floatingx.util.FX_GRAVITY_BOTTOM
import com.petterp.floatingx.util.FX_GRAVITY_TOP
import com.petterp.floatingx.util.coerceInFx

/** 基础悬浮窗View */
@SuppressLint("ViewConstructor")
class FxManagerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private lateinit var helper: BasisHelper
    private var mLastTouchDownTime = 0L
    private var mParentWidth = 0f
    private var mParentHeight = 0f

    private var isNearestLeft = true
    private var downTouchX = 0f
    private var downTouchY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var touchDownId = 0

    private var minHBoundary = 0f
    private var maxHBoundary = 0f
    private var minWBoundary = 0f
    private var maxWBoundary = 0f

    private var isClickEnable = true
    private var isMoveLoading = false
    private var scaledTouchSlop = 0

    private var restoreHelper: FxLocationRestoreHelper = FxLocationRestoreHelper()
    private var parentChangeListener = OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        refreshLocation(v.width, v.height)
    }

    private var _childFxView: View? = null
    val childFxView: View? get() = _childFxView
    private var mMoveAnimator: MoveAnimator = MoveAnimator()

    @JvmSynthetic
    internal fun init(config: BasisHelper): FxManagerView {
        this.helper = config
        initView()
        return this
    }

    private fun initView() {
        _childFxView = inflateLayoutView() ?: inflateLayoutId()
        checkNotNull(_childFxView) { "initFxView -> Error,check your layoutId or layoutView." }
        initLocation()
        isClickable = true
        scaledTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
        helper.iFxViewLifecycle?.initView(this)
        // 注意这句代码非常重要,可以避免某些情况下View被隐藏掉
        setBackgroundColor(Color.TRANSPARENT)
    }

    private fun inflateLayoutView(): View? {
        val view = helper.layoutView ?: return null
        helper.fxLog?.d("fxView-->init, way:[layoutView]")
        val lp = view.layoutParams ?: LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addView(view, lp)
        return view
    }

    private fun inflateLayoutId(): View? {
        if (helper.layoutId == 0) return null
        helper.fxLog?.d("fxView-->init, way:[layoutId]")
        return inflate(context, helper.layoutId, this)
    }

    private fun initLocation() {
        // 初始化lp
        val configImpl = helper.iFxConfigStorage
        val hasConfig = configImpl?.hasConfig() ?: false
        val lp = helper.layoutParams ?: LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        )
        // 不存在历史坐标时,设置gravity,默认左上角
        if (!hasConfig) lp.gravity = helper.gravity.value
        layoutParams = lp

        // 获得浮窗的位置
        // 存在历史位置 || 根据配置去获取
        val (initX, initY) = if (hasConfig) {
            configImpl!!.getX() to configImpl.getY()
        } else {
            initDefaultXY()
        }
        if (initX != -1F) x = initX
        if (initY != -1F) y = initY
        helper.fxLog?.d("fxView->initLocation,isHasConfig-($hasConfig),defaultX-($initX),defaultY-($initY)")
    }

    private fun initDefaultXY(): Pair<Float, Float> {
        // 非辅助定位&&非默认位置,此时x,y不可信
        if (!helper.enableAssistLocation && !helper.gravity.isDefault()) {
            helper.fxLog?.e(
                "fxView--默认坐标可能初始化异常,如果显示位置异常,请检查您的gravity是否为默认配置，当前gravity:${helper.gravity}。\n" +
                        "如果您要配置gravity,建议您启用辅助定位setEnableAssistDirection(),此方法将更便于定位。"
            )
        }
        return helper.defaultX to checkDefaultY(helper.defaultY)
    }

    private fun checkDefaultY(y: Float): Float {
        // 单独处理状态栏和底部导航栏
        var defaultY = y
        when (helper.gravity.scope) {
            FX_GRAVITY_TOP -> defaultY += helper.statsBarHeight
            FX_GRAVITY_BOTTOM -> defaultY -= helper.navigationBarHeight
            else -> {}
        }
        return defaultY
    }

    /**
     * 移动浮窗到指定位置，该方法会帮助你处理越界问题
     * @param x 要移动到的x坐标
     * @param y 要移动到的y坐标
     * @param useAnimation 是否使用动画
     * */
    @JvmOverloads
    fun moveLocation(x: Float, y: Float, useAnimation: Boolean = true) {
        val newX = x.coerceInFx(minWBoundary, maxWBoundary)
        val newY = y.coerceInFx(minHBoundary, maxHBoundary)
        if (useAnimation) {
            moveToLocation(newX, newY)
        } else {
            this.x = x
            this.y = y
        }
    }

    /**
     * 按照向量移动浮窗，该方法会帮你处理越界问题
     * @param x x坐标要增加或减少的值
     * @param y y坐标要增加或减少的值
     * @param useAnimation 是否使用动画
     * */
    @JvmOverloads
    fun moveLocationByVector(x: Float, y: Float, useAnimation: Boolean = true) {
        val currentX = this.x.plus(x)
        val currentY = this.y.plus(y)
        moveLocation(currentX, currentY, useAnimation)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        var intercepted = false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initTouchDown(ev)
                helper.fxLog?.d("fxView---onInterceptTouchEvent-[down],interceptedTouch-$intercepted")
            }

            MotionEvent.ACTION_MOVE -> {
                intercepted = kotlin.math.abs(downTouchX - ev.x) >= scaledTouchSlop
                helper.fxLog?.v("fxView---onInterceptTouchEvent-[move], interceptedTouch-$intercepted")
            }
        }
        return intercepted
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        helper.iFxScrollListener?.eventIng(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (touchDownId == INVALID_TOUCH_ID) {
                    val eventX = event.getX(event.actionIndex)
                    val eventY = event.getY(event.actionIndex)
                    if (eventX >= 0 && eventX <= width && eventY >= 0 && eventY <= height) {
                        initTouchDown(event)
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (touchDownId == INVALID_TOUCH_ID || !helper.enableTouch) {
                    return super.onTouchEvent(event)
                }
                val pointIdx = event.findPointerIndex(touchDownId)
                if (pointIdx != INVALID_TOUCH_IDX) {
                    updateLocation(event, pointIdx)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == touchDownId) {
                    actionTouchCancel()
                    helper.fxLog?.d("fxView---onTouchEvent--ACTION_POINTER_UP---clearTouchId->")
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                helper.fxLog?.d("fxView---onTouchEvent--End")
                actionTouchCancel()
                clickManagerView()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        helper.iFxViewLifecycle?.attach()
        (parent as? ViewGroup)?.addOnLayoutChangeListener(parentChangeListener)
        helper.fxLog?.d("fxView-lifecycle-> onAttachedToWindow")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        helper.iFxViewLifecycle?.detached()
        (parent as? ViewGroup)?.removeOnLayoutChangeListener(parentChangeListener)
        helper.fxLog?.d("fxView-lifecycle-> onDetachedFromWindow")
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        helper.iFxViewLifecycle?.windowsVisibility(visibility)
        helper.fxLog?.d("fxView-lifecycle-> onWindowVisibilityChanged")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        helper.fxLog?.d("fxView--lifecycle-> onConfigurationChanged--->")
        // use the configuration in Configuration first
        val isScreenChanged = restoreHelper.updateConfig(newConfig)
        if (!isScreenChanged) return
        val x = x
        val y = y
        restoreHelper.saveLocation(x, y, mParentWidth, helper)
        helper.fxLog?.d("fxView--lifecycle-> saveLocation:[x:$x,y:$y]")
    }

    private fun clickManagerView() {
        if (helper.enableClickListener && isClickEnable && helper.iFxClickListener != null && isOnClickEvent()) {
            isClickEnable = false
            helper.iFxClickListener!!.onClick(this)
            postDelayed({ isClickEnable = true }, helper.clickTime)
            helper.fxLog?.d("fxView -> click")
        }
    }

    private fun actionTouchCancel() {
        helper.iFxScrollListener?.up()
        touchDownId = INVALID_TOUCH_ID
        moveToEdge()
    }

    private fun isOnClickEvent(): Boolean {
        return System.currentTimeMillis() - mLastTouchDownTime < TOUCH_TIME_THRESHOLD
    }

    private fun updateBoundary(isDownTouchInit: Boolean) {
        // 开启边缘回弹时,浮窗允许移动到边界外
        if (helper.enableEdgeRebound) {
            val edgeOffset = if (isDownTouchInit) 0f else helper.edgeOffset
            val marginTop = if (isDownTouchInit) 0f else helper.fxBorderMargin.t + edgeOffset
            val marginBto = if (isDownTouchInit) 0f else helper.fxBorderMargin.b + edgeOffset
            val marginLef = if (isDownTouchInit) 0f else helper.fxBorderMargin.l + edgeOffset
            val marginRig = if (isDownTouchInit) 0f else helper.fxBorderMargin.r + edgeOffset
            minWBoundary = marginLef
            maxWBoundary = mParentWidth - marginRig
            minHBoundary = helper.statsBarHeight.toFloat() + marginTop
            maxHBoundary = mParentHeight - helper.navigationBarHeight - marginBto
        } else {
            minWBoundary = helper.fxBorderMargin.l
            maxWBoundary = mParentWidth - helper.fxBorderMargin.r
            minHBoundary = helper.statsBarHeight + helper.fxBorderMargin.t
            maxHBoundary = mParentHeight - helper.navigationBarHeight - helper.fxBorderMargin.b
        }
    }

    private fun initTouchDown(ev: MotionEvent) {
        updateWidgetSize()
        updateBoundary(true)
        touchDownId = ev.getPointerId(ev.actionIndex)
        downTouchX = ev.getX(ev.actionIndex)
        downTouchY = ev.getY(ev.actionIndex)
        // init width and height boundary
        mMoveAnimator.stop()
        helper.iFxScrollListener?.down()
        if (helper.enableClickListener) mLastTouchDownTime = System.currentTimeMillis()
        helper.fxLog?.d("fxView---newTouchDown:$touchDownId")
    }

    private fun refreshLocation(w: Int, h: Int) {
        if (!updateWidgetSize(w, h)) return
        if (restoreHelper.isRestoreLocation()) {
            restoreLocation()
        } else {
            moveToEdge(isUpdateBoundary = false)
        }
    }

    private fun updateLocation(event: MotionEvent, pointIndex: Int) {
        val disX = x.plus(event.getX(pointIndex)).minus(downTouchX)
            .coerceInFx(minWBoundary, maxWBoundary)
        val disY = y.plus(event.getY(pointIndex)).minus(downTouchY)
            .coerceInFx(minHBoundary, maxHBoundary)
        x = disX
        y = disY
        helper.iFxScrollListener?.dragIng(event, disX, disY)
        helper.fxLog?.v("fxView---scrollListener--drag-event--x($disX)-y($disY)")
    }

    @JvmSynthetic
    internal fun restoreLocation(x: Float, y: Float) {
        (layoutParams as LayoutParams).gravity = FxGravity.DEFAULT.value
        this.x = x
        this.y = y
    }

    private fun restoreLocation() {
        val (x, y) = restoreHelper.getLocation(
            minWBoundary,
            maxWBoundary,
            minHBoundary,
            maxHBoundary
        )
        this.x = x
        this.y = y
        saveLocationToStorage(x, y)
        helper.fxLog?.d("fxView--lifecycle-> restoreLocation:[x:$x,y:$y]")
    }

    private fun updateWidgetSize(): Boolean {
        // 如果此时浮窗被父布局移除,parent将为null,此时就别更新位置了,没意义
        val parentGroup = (parent as? ViewGroup) ?: return false
        return updateWidgetSize(parentGroup.width, parentGroup.height)
    }

    private fun updateWidgetSize(parentW: Int, parentH: Int): Boolean {
        val parentWidth = (parentW - this@FxManagerView.width).toFloat()
        val parentHeight = (parentH - this@FxManagerView.height).toFloat()
        if (mParentHeight != parentHeight || mParentWidth != parentWidth) {
            helper.fxLog?.d("fxView->updateContainerSize: oldW-($mParentWidth),oldH-($mParentHeight),newW-($parentWidth),newH-($parentHeight)")
            mParentWidth = parentWidth
            mParentHeight = parentHeight
            updateBoundary(false)
            return true
        }
        return false
    }

    private fun isNearestLeft(): Boolean {
        val middle = mParentWidth / 2
        isNearestLeft = x < middle
        return isNearestLeft
    }

    @JvmSynthetic
    internal fun moveToEdge(isLeft: Boolean = isNearestLeft(), isUpdateBoundary: Boolean = true) {
        if (isMoveLoading) return
        if (isUpdateBoundary) updateBoundary(false)
        // 允许边缘吸附
        if (helper.enableEdgeAdsorption) {
            val moveY = y.coerceInFx(minHBoundary, maxHBoundary)
            val moveX = if (isLeft) minWBoundary else maxWBoundary
            moveToLocation(moveX, moveY)
        } else if (helper.enableEdgeRebound) {
            val moveX = x.coerceInFx(minWBoundary, maxWBoundary)
            val moveY = y.coerceInFx(minHBoundary, maxHBoundary)
            moveToLocation(moveX, moveY)
        }
    }

    private fun moveToLocation(moveX: Float, moveY: Float) {
        isMoveLoading = true
        if (moveX == x && moveY == y) {
            isMoveLoading = false
            return
        }
        helper.fxLog?.d("fxView-->moveToEdge---x-($x)，y-($y) ->  moveX-($moveX),moveY-($moveY)")
        mMoveAnimator.start(moveX, moveY)
        currentX = moveX
        currentY = moveY
        saveLocationToStorage(moveX, moveY)
    }

    private fun saveLocationToStorage(moveX: Float, moveY: Float) {
        if (!helper.enableSaveDirection) return
        if (helper.iFxConfigStorage == null) {
            helper.fxLog?.e("fxView-->saveDirection---iFxConfigStorageImpl does not exist, save failed!")
            return
        }
        helper.iFxConfigStorage?.update(moveX, moveY)
        helper.fxLog?.d("fxView-->saveDirection---x-($moveX)，y-($moveY)")
    }

    private inner class MoveAnimator : Runnable {
        private var destinationX = 0f
        private var destinationY = 0f
        private var startingTime: Long = 0
        fun start(x: Float, y: Float) {
            destinationX = x
            destinationY = y
            startingTime = System.currentTimeMillis()
            HANDLER.post(this)
        }

        override fun run() {
            if (childFxView == null || childFxView?.parent == null) return
            val progress =
                MAX_PROGRESS.coerceAtMost((System.currentTimeMillis() - startingTime) / DEFAULT_MOVE_ANIMATOR_DURATION)
            x += (destinationX - x) * progress
            y += (destinationY - y) * progress
            currentX = x
            currentY = y
            if (progress < MAX_PROGRESS) {
                HANDLER.post(this)
            } else {
                isMoveLoading = false
            }
        }

        fun stop() {
            isMoveLoading = false
            HANDLER.removeCallbacks(this)
        }
    }

    companion object {
        private const val INVALID_TOUCH_ID = -1
        private const val INVALID_TOUCH_IDX = -1
        private const val TOUCH_TIME_THRESHOLD = 150L
        private const val MAX_PROGRESS = 1f
        private const val DEFAULT_MOVE_ANIMATOR_DURATION = 400f
        private val HANDLER = Handler(Looper.getMainLooper())
    }
}
