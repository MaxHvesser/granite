package no.mhl.showroom.ui

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.Coil
import coil.ImageLoader
import coil.util.CoilUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import no.mhl.showroom.Constants.ANIM_DURATION
import no.mhl.showroom.Constants.MAX_ALPHA
import no.mhl.showroom.Constants.MIN_ALPHA
import no.mhl.showroom.R
import no.mhl.showroom.data.model.GalleryData
import no.mhl.showroom.data.preloadUpcomingImages
import no.mhl.showroom.ui.adapter.ImagePagerAdapter
import no.mhl.showroom.ui.adapter.ThumbnailRecyclerAdapter
import no.mhl.showroom.util.indexOrigin
import no.mhl.showroom.util.setCount
import no.mhl.showroom.util.setDescription
import okhttp3.OkHttpClient


class Showroom
constructor(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    // region View Properties
    private lateinit var parentActivity: AppCompatActivity
    private val parentView by lazy { findViewById<ConstraintLayout>(R.id.parent_view) }
    private val imageViewPager by lazy { findViewById<ViewPager2>(R.id.image_recycler) }
    private val thumbnailRecycler by lazy { findViewById<RecyclerView>(R.id.thumbnail_recycler) }
    private val thumbnailRecyclerContainer by lazy { findViewById<ConstraintLayout>(R.id.thumbnail_recycler_container) }
    private val toolbar by lazy { findViewById<Toolbar>(R.id.toolbar) }
    private val descriptionText by lazy { findViewById<TextView>(R.id.description) }
    private val countText by lazy { findViewById<TextView>(R.id.image_counter) }
    // endregion

    // region Data Properties
    private lateinit var galleryData: List<GalleryData>
    private lateinit var imagePagerAdapter: ImagePagerAdapter
    private lateinit var thumbnailRecyclerAdapter: ThumbnailRecyclerAdapter
    private var originalStatusBarColor: Int = 0
    private var originalNavigationBarColor: Int = 0
    private var initialPosition: Int = 0
    private var isImmersive: Boolean = false
    private val hiddenFlags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE

    private val visibleFlags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    // endregion

    // region Custom Attributes
    private var fontPrimary: Typeface? = Typeface.DEFAULT
    private var fontSecondary: Typeface? = Typeface.DEFAULT
    private var imagePreloadLimit: Int = 3
    // endregion

    // region IO Event Properties
    private var onBackNavigationPressed: ((position: Int) -> Unit)? = null
    // endregion

    // region Initialisation
    init {
        LayoutInflater.from(context).inflate(R.layout.layout_showroom, this)

        context.theme.obtainStyledAttributes(
            attrs,
            R.styleable.Showroom,
            0, 0
        ).apply(::setupCustomAttributes)

        setupCoil()
    }

    private fun setupCoil() {
        Coil.setDefaultImageLoader {
            ImageLoader(context) {
                crossfade(true)
                okHttpClient {
                    OkHttpClient.Builder()
                        .cache(CoilUtils.createDefaultCache(context))
                        .build()
                }
            }
        }
    }
    // endregion

    // region View Setup
    private fun setupCustomAttributes(typedArray: TypedArray) {
        try {
            val fontPrimaryRes = typedArray.getResourceId(R.styleable.Showroom_fontFamilyPrimary, 0)
            val fontSecondaryRes =
                typedArray.getResourceId(R.styleable.Showroom_fontFamilySecondary, 0)
            val preloadLimit = typedArray.getInteger(R.styleable.Showroom_imagePreloadLimit, 3)

            if (fontPrimaryRes != 0) {
                fontPrimary = ResourcesCompat.getFont(context, fontPrimaryRes)
            }

            if (fontSecondaryRes != 0) {
                fontSecondary = ResourcesCompat.getFont(context, fontSecondaryRes)
            }

            descriptionText.typeface = fontPrimary
            countText.typeface = fontSecondary
            imagePreloadLimit = preloadLimit
        } finally {
            typedArray.recycle()
        }
    }

    fun attach(activity: AppCompatActivity, data: List<GalleryData>, openAtIndex: Int = 0) {
        parentActivity = activity
        galleryData = data
        initialPosition = openAtIndex.indexOrigin
        originalStatusBarColor = activity.window.statusBarColor
        originalNavigationBarColor = activity.window.navigationBarColor
        setupViews()
    }

    private fun setupViews() {
        setupEdgeToEdge()
        setupImageViewPager()
        setupThumbnailRecycler()
        setupToolbar()
        setInitialPositionIfApplicable()
    }

    private fun setupEdgeToEdge() {
        parentView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        parentActivity.window.apply {
            navigationBarColor = Color.TRANSPARENT
            statusBarColor = Color.TRANSPARENT
        }

        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            v.updatePadding(top = insets.systemWindowInsetTop)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(thumbnailRecyclerContainer) { v, insets ->
            v.updatePadding(bottom = insets.systemWindowInsetBottom)
            insets
        }
    }

    private fun setupImageViewPager() {
        imagePagerAdapter = ImagePagerAdapter(galleryData)
        imageViewPager.apply {
            adapter = imagePagerAdapter
            offscreenPageLimit = imagePreloadLimit
        }

        imagePagerAdapter.onImageClicked = {
            toggleImmersion()
            isImmersive = isImmersive.not()
            toggleGalleryUi(isImmersive)
        }

        imageViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                thumbnailRecycler.smoothScrollToPosition(position)
                setThumbnailAsSelected(position)
                preload(position)
            }
        })
    }

    private fun preload(position: Int) = CoroutineScope(Dispatchers.IO).launch {
        preloadUpcomingImages(context, position, galleryData, imagePreloadLimit)
    }

    private fun setupThumbnailRecycler() {
        thumbnailRecyclerAdapter = ThumbnailRecyclerAdapter(galleryData)
        thumbnailRecycler.apply {
            setHasFixedSize(true)
            adapter = thumbnailRecyclerAdapter
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            setItemViewCacheSize(imagePreloadLimit)
        }
        thumbnailRecyclerAdapter.onThumbnailClicked = { position ->
            imageViewPager.setCurrentItem(position, false)
            setThumbnailAsSelected(position)
        }

        setThumbnailAsSelected(0)
    }

    private fun setInitialPositionIfApplicable() {
        if (initialPosition > 0 && initialPosition < galleryData.size) {
            imageViewPager.setCurrentItem(initialPosition, false)
            thumbnailRecycler.scrollToPosition(initialPosition)
            setThumbnailAsSelected(initialPosition)
        }
    }

    private fun setupToolbar() {
        parentActivity.setSupportActionBar(toolbar)
        parentActivity.supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(false)
            setHomeAsUpIndicator(R.drawable.ic_clear)
        }

        toolbar.setNavigationOnClickListener {
            parentView.systemUiVisibility = 0
            parentActivity.window.decorView.systemUiVisibility = 0

            parentActivity.window.apply {
                navigationBarColor = originalNavigationBarColor
                statusBarColor = originalStatusBarColor
            }

            onBackNavigationPressed?.invoke(galleryData.indexOf(galleryData.first { it.selected }))
        }
    }
    // endregion

    // region Show/Hide System UI
    private fun toggleGalleryUi(hide: Boolean) {
        fun fade(view: View) {
            ViewCompat
                .animate(view)
                .alpha(if (hide) MIN_ALPHA else MAX_ALPHA)
                .setDuration(ANIM_DURATION)
                .withStartAction {
                    if (hide.not()) {
                        view.visibility = View.VISIBLE
                    }
                }
                .withEndAction {
                    if (hide) {
                        view.visibility = View.GONE
                    }
                }
                .start()
        }

        fun translateY(view: View) {
            ViewCompat
                .animate(view)
                .translationY(if (hide) view.height.toFloat() else 0f)
                .setDuration(ANIM_DURATION)
                .start()
        }

        fade(toolbar)
        fade(descriptionText)
        fade(countText)
        translateY(thumbnailRecyclerContainer)
    }

    private fun toggleImmersion() {
        parentActivity.window.decorView.apply {
            setSystemUiVisibility(when (isImmersive) {
                true -> visibleFlags
                else -> hiddenFlags
            })
        }
    }
    // endregion

    // region Thumbnail Selection
    private fun setThumbnailAsSelected(position: Int) {
        val currentPosition = galleryData.indexOf(galleryData.find { it.selected })

        if (currentPosition != position) {
            if (position <= galleryData.size && position >= 0) {
                countText.setCount(position, galleryData.size)
                descriptionText.setDescription(galleryData[position].description)

                galleryData.find { it.selected }?.selected = false
                galleryData[position].selected = true

                thumbnailRecyclerAdapter.notifyDataSetChanged()
            }
        }
    }
    // endregion

    // region IO Events
    fun setBackPressedEvent(backPressedEvent: ((position: Int) -> Unit)) {
        onBackNavigationPressed = backPressedEvent
    }
    // endregion

}