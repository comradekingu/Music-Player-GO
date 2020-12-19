package com.iven.musicplayergo.fragments

import android.animation.Animator
import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.text.parseAsHtml
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.load
import coil.request.ImageRequest
import com.afollestad.recyclical.datasource.dataSourceOf
import com.afollestad.recyclical.setup
import com.afollestad.recyclical.withItem
import com.google.android.material.card.MaterialCardView
import com.iven.musicplayergo.GoConstants
import com.iven.musicplayergo.MusicViewModel
import com.iven.musicplayergo.R
import com.iven.musicplayergo.databinding.FragmentDetailsBinding
import com.iven.musicplayergo.extensions.*
import com.iven.musicplayergo.goPreferences
import com.iven.musicplayergo.helpers.DialogHelper
import com.iven.musicplayergo.helpers.ListsHelper
import com.iven.musicplayergo.helpers.MusicOrgHelper
import com.iven.musicplayergo.helpers.ThemeHelper
import com.iven.musicplayergo.models.Album
import com.iven.musicplayergo.models.Music
import com.iven.musicplayergo.ui.AlbumsViewHolder
import com.iven.musicplayergo.ui.GenericViewHolder
import com.iven.musicplayergo.ui.UIControlInterface
import kotlinx.coroutines.*


/**
 * A simple [Fragment] subclass.
 * Use the [DetailsFragment.newInstance] factory method to
 * create an instance of this fragment.
 */

class DetailsFragment : Fragment(R.layout.fragment_details), SearchView.OnQueryTextListener {

    private lateinit var mDetailsFragmentBinding: FragmentDetailsBinding

    // View model
    private lateinit var mMusicViewModel: MusicViewModel

    private lateinit var mArtistDetailsAnimator: Animator
    private lateinit var mAlbumsRecyclerViewLayoutManager: LinearLayoutManager

    private val mSelectedAlbumsDataSource = dataSourceOf()
    private val mSongsDataSource = dataSourceOf()

    private var mLaunchedBy = GoConstants.ARTIST_VIEW

    private var mSelectedArtistAlbums: List<Album>? = null
    private var mSongsList: List<Music>? = null

    private var mSelectedArtistOrFolder: String? = null
    private var mSelectedAlbumPosition = -1

    private lateinit var mUIControlInterface: UIControlInterface

    private var mSelectedAlbum: Album? = null

    private var mSongsSorting = GoConstants.TRACK_SORTING

    private val sLaunchedByArtistView get() = mLaunchedBy == GoConstants.ARTIST_VIEW
    private val sLaunchedByFolderView get() = mLaunchedBy == GoConstants.FOLDER_VIEW

    private var mShuffledAlbum: String? = null
    private var sWasShuffling: Pair<Boolean, String?> = Pair(false, null)

    /**
     * This is the job for all coroutines started by this ViewModel.
     * Cancelling this job will cancel all coroutines started by this ViewModel.
     */
    private val mLoadCoverJob = SupervisorJob()

    private val mLoadCoverHandler = CoroutineExceptionHandler { _, exception ->
        exception.printStackTrace()
    }

    private val mLoadCoverIoDispatcher = Dispatchers.IO + mLoadCoverJob + mLoadCoverHandler
    private val mLoadCoverIoScope = CoroutineScope(mLoadCoverIoDispatcher)

    private var sIsCovers = false
    private lateinit var mImageLoader: ImageLoader

    override fun onAttach(context: Context) {
        super.onAttach(context)

        arguments?.getString(TAG_ARTIST_FOLDER)?.let { selectedArtistOrFolder ->
            mSelectedArtistOrFolder = selectedArtistOrFolder
        }

        arguments?.getString(TAG_IS_FOLDER)?.let { launchedBy ->
            mLaunchedBy = launchedBy
        }

        if (sLaunchedByArtistView) {
            arguments?.getInt(TAG_SELECTED_ALBUM_POSITION)?.let { selectedAlbumPosition ->
                mSelectedAlbumPosition = selectedAlbumPosition
            }
        }

        arguments?.getBoolean(TAG_IS_SHUFFLING)?.let { isShuffleMode ->
            if (isShuffleMode) {
                mSongsSorting = GoConstants.SHUFFLE_SORTING
            }
        }

        arguments?.getString(TAG_SHUFFLED_ALBUM)?.let { selectedAlbum ->
            mShuffledAlbum = selectedAlbum
        }

        // This makes sure that the container activity has implemented
        // the callback interface. If not, it throws an exception
        try {
            mUIControlInterface = activity as UIControlInterface
        } catch (e: ClassCastException) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (sIsCovers) {
            mLoadCoverJob.cancel()
        }
    }

    fun onHandleBackPressed(): Animator {
        if (!mArtistDetailsAnimator.isRunning) {
            mArtistDetailsAnimator =
                mDetailsFragmentBinding.root.createCircularReveal(
                    isErrorFragment = false,
                    show = false
                )
        }
        return mArtistDetailsAnimator
    }

    // https://stackoverflow.com/a/38241603
    private fun getTitleTextView(toolbar: Toolbar) = try {
        val toolbarClass = Toolbar::class.java
        val titleTextViewField = toolbarClass.getDeclaredField("mTitleTextView")
        titleTextViewField.isAccessible = true
        titleTextViewField.get(toolbar) as TextView
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    private fun getSongSource(): List<Music>? {
        return when (mLaunchedBy) {
            GoConstants.ARTIST_VIEW -> {
                mMusicViewModel.deviceAlbumsByArtist?.get(mSelectedArtistOrFolder)
                    ?.let { selectedArtistAlbums ->
                        mSelectedArtistAlbums = selectedArtistAlbums
                    }
                mMusicViewModel.deviceSongsByArtist?.get(mSelectedArtistOrFolder)
            }

            GoConstants.FOLDER_VIEW ->
                mMusicViewModel.deviceMusicByFolder?.get(mSelectedArtistOrFolder)

            else ->
                mMusicViewModel.deviceMusicByAlbum?.get(mSelectedArtistOrFolder)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mDetailsFragmentBinding = FragmentDetailsBinding.bind(view)

        mMusicViewModel =
            ViewModelProvider(requireActivity()).get(MusicViewModel::class.java).apply {
                deviceMusic.observe(viewLifecycleOwner, { returnedMusic ->
                    if (!returnedMusic.isNullOrEmpty()) {
                        mSongsList = getSongSource()

                        setupToolbar()

                        setupViews(view)
                    }
                })
            }
    }

    private fun setupToolbar() {
        mDetailsFragmentBinding.detailsToolbar.run {

            overflowIcon = AppCompatResources.getDrawable(
                requireActivity(),
                if (sLaunchedByArtistView) {
                    R.drawable.ic_shuffle
                } else {
                    R.drawable.ic_more_vert
                }
            )

            title = mSelectedArtistOrFolder

            // Make toolbar's title scrollable
            getTitleTextView(this)?.let { tV ->
                tV.isSelected = true
                tV.setHorizontallyScrolling(true)
                tV.ellipsize = TextUtils.TruncateAt.MARQUEE
                tV.marqueeRepeatLimit = -1
            }

            setupToolbarSpecs()

            setNavigationOnClickListener {
                requireActivity().onBackPressed()
            }

            setupMenu()
        }
    }

    private fun setupToolbarSpecs() {
        mDetailsFragmentBinding.detailsToolbar.run {
            elevation = if (!sLaunchedByArtistView) {
                resources.getDimensionPixelSize(R.dimen.search_bar_elevation).toFloat()
            } else {
                0F
            }

            val params = layoutParams as LinearLayout.LayoutParams
            params.bottomMargin = if (!sLaunchedByArtistView) {
                0
            } else {
                resources.getDimensionPixelSize(R.dimen.player_controls_padding_normal)
            }
        }
    }

    private fun setupViews(view: View) {

        if (sLaunchedByArtistView) {
            sIsCovers = goPreferences.isCovers
            if (sIsCovers) {
                mImageLoader = ImageLoader.Builder(requireActivity())
                        .bitmapPoolingEnabled(false)
                        .crossfade(true)
                        .build()
            }
            mSelectedAlbum = when {
                mSelectedAlbumPosition != -1 -> mSelectedArtistAlbums?.get(
                    mSelectedAlbumPosition
                )
                else -> {
                    mSelectedAlbumPosition = 0
                    mSelectedArtistAlbums?.get(0)
                }
            }

            if (mSongsSorting == GoConstants.SHUFFLE_SORTING) {
                sWasShuffling = Pair(true, mShuffledAlbum)
            }

            setupAlbumsContainer()

            mDetailsFragmentBinding.sortButton.run {
                setOnClickListener {
                    mSongsSorting = ListsHelper.getSongsSorting(mSongsSorting)
                    updateSorting(mSongsSorting)
                }
            }

            mDetailsFragmentBinding.queueAddButton.setOnClickListener {
                mUIControlInterface.onAddAlbumToQueue(
                    mSelectedAlbum?.music,
                    Pair(true, mSelectedAlbum?.music?.get(0)),
                    isLovedSongs = false,
                    isShuffleMode = sWasShuffling.first,
                    clearShuffleMode = !sWasShuffling.first,
                    mLaunchedBy
                )
            }

        } else {

            mDetailsFragmentBinding.albumsRv.handleViewVisibility(false)
            mDetailsFragmentBinding.selectedAlbumContainer.handleViewVisibility(false)

            mDetailsFragmentBinding.detailsToolbar.subtitle = getString(
                R.string.folder_info,
                mSongsList?.size
            )

            val searchView =
                mDetailsFragmentBinding.detailsToolbar.menu.findItem(R.id.action_search).actionView as SearchView
            searchView.run {
                setOnQueryTextListener(this@DetailsFragment)
                setOnQueryTextFocusChangeListener { _, hasFocus ->
                    mDetailsFragmentBinding.detailsToolbar.menu.setGroupVisible(
                        R.id.more_options_folder,
                        !hasFocus
                    )
                }
            }
        }

        setSongsDataSource(
            if (sLaunchedByArtistView) {
                mSelectedAlbum?.music
            } else {
                mSongsList
            }
        )

        mDetailsFragmentBinding.songsRv.run {

            // setup{} is an extension method on RecyclerView
            setup {
                // item is a `val` in `this` here
                withDataSource(mSongsDataSource)

                withItem<Music, GenericViewHolder>(R.layout.generic_item) {
                    onBind(::GenericViewHolder) { _, item ->

                        val displayedTitle =
                            if (goPreferences.songsVisualization != GoConstants.TITLE) {
                                item.displayName?.toFilenameWithoutExtension()
                            } else {
                                getString(
                                    R.string.track_song,
                                    item.track.toFormattedTrack(),
                                    item.title
                                ).parseAsHtml()
                            }

                        // GenericViewHolder is `this` here
                        title.text = displayedTitle
                        subtitle.text = item.duration.toFormattedDuration(
                            isAlbum = false,
                            isSeekBar = false
                        )
                    }

                    onClick {

                        val selectedPlaylist =
                            if (sLaunchedByFolderView) {
                                if (sWasShuffling.first && item.album == mSelectedArtistOrFolder) {
                                    mSongsList = getSongSource()
                                }
                                mSongsList
                            } else {
                                val playlist = MusicOrgHelper.getAlbumSongs(
                                    item.artist,
                                    item.album,
                                    mMusicViewModel.deviceAlbumsByArtist
                                )
                                playlist
                            }

                        mUIControlInterface.onSongSelected(
                            item,
                            selectedPlaylist,
                            mLaunchedBy
                        )
                    }

                    onLongClick { index ->
                        DialogHelper.showPopupForSongs(
                            requireActivity(),
                            findViewHolderForAdapterPosition(index)?.itemView,
                            item,
                            mLaunchedBy,
                            mUIControlInterface
                        )
                    }
                }
            }

            addBidirectionalSwipeHandler(false) { viewHolder: RecyclerView.ViewHolder,
                                                  _: Int ->
                mUIControlInterface.onAddToQueue(
                    mSongsList!![viewHolder.adapterPosition],
                    mLaunchedBy
                )
                adapter?.notifyDataSetChanged()
            }
        }

        view.afterMeasured {
            mArtistDetailsAnimator =
                mDetailsFragmentBinding.root.createCircularReveal(
                    isErrorFragment = false,
                    show = true
                )
        }
    }

    private fun setAlbumsDataSource(albumsList: List<Album>?) {
        albumsList?.let { albums ->
            mSelectedAlbumsDataSource.set(albums)
        }
    }

    private fun setSongsDataSource(musicList: List<Music>?) {
        if (sLaunchedByArtistView) {
            mDetailsFragmentBinding.sortButton.run {

                if (sWasShuffling.first && sWasShuffling.second == mSelectedAlbum?.title) {
                    mDetailsFragmentBinding.sortButton.setImageResource(
                        ThemeHelper.resolveSortAlbumSongsIcon(
                            mSongsSorting
                        )
                    )
                } else {
                    isEnabled = mSelectedAlbum?.music?.size!! >= 2
                    ThemeHelper.updateIconTint(
                        this,
                        if (isEnabled) {
                            ContextCompat.getColor(requireActivity(), R.color.widgetsColor)
                        } else {
                            ThemeHelper.resolveColorAttr(
                                requireActivity(),
                                android.R.attr.colorButtonNormal
                            )
                        }
                    )
                }
            }
            mDetailsFragmentBinding.detailsToolbar.menu.findItem(R.id.action_shuffle_sa).isEnabled =
                mSelectedAlbum?.music?.size!! >= 2
        }

        musicList?.let { songs ->
            mSongsDataSource.set(songs)
        }
    }

    private fun updateSorting(sorting: Int) {
        mDetailsFragmentBinding.sortButton.setImageResource(
            ThemeHelper.resolveSortAlbumSongsIcon(
                sorting
            )
        )
        setSongsDataSource(
            ListsHelper.getSortedMusicList(
                sorting,
                mSelectedAlbum?.music
            )
        )
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        setSongsDataSource(
            ListsHelper.processQueryForMusic(newText, mSongsList)
                ?: mSongsList
        )
        return false
    }

    override fun onQueryTextSubmit(query: String?) = false

    private fun setupMenu() {

        mDetailsFragmentBinding.detailsToolbar.run {

            val menuToInflate = when {
                sLaunchedByArtistView -> R.menu.menu_artist_details
                sLaunchedByFolderView -> R.menu.menu_album_details
                else -> R.menu.menu_album_details
            }

            inflateMenu(menuToInflate)

            menu.run {
                findItem(R.id.action_shuffle_am).isEnabled =
                    if (sLaunchedByArtistView) {
                        mSelectedArtistAlbums?.size!! >= 2
                    } else {
                        mSongsList?.size!! >= 2
                    }

                findItem(R.id.action_shuffle_sa).isEnabled = sLaunchedByArtistView
                if (!sLaunchedByArtistView) {
                    findItem(R.id.sorting).isEnabled =
                        mSongsList?.size!! >= 2
                }
            }

            setOnMenuItemClickListener {

                when (it.itemId) {
                    R.id.action_add_queue -> mUIControlInterface.onAddAlbumToQueue(
                        mSongsList?.toMutableList(),
                        Pair(true, mSongsList?.get(0)),
                        isLovedSongs = false,
                        isShuffleMode = sWasShuffling.first,
                        clearShuffleMode = !sWasShuffling.first,
                        mLaunchedBy
                    )
                    R.id.action_shuffle_am -> mUIControlInterface.onShuffleSongs(
                        null,
                        null,
                        mSongsList?.toMutableList(),
                        mSongsList?.size!! < 30, // only queue if album size don't exceed 30
                        mLaunchedBy
                    )
                    R.id.action_shuffle_sa -> {
                        sWasShuffling = Pair(true, mSelectedAlbum?.title)
                        val music = mUIControlInterface.onShuffleSongs(
                            mSelectedAlbum?.title,
                            mSelectedArtistAlbums,
                            mSelectedAlbum?.music,
                            true,
                            mLaunchedBy
                        )
                        mSongsSorting = GoConstants.SHUFFLE_SORTING

                        if (sLaunchedByArtistView) {
                            mDetailsFragmentBinding.sortButton.setImageResource(
                                ThemeHelper.resolveSortAlbumSongsIcon(
                                    mSongsSorting
                                )
                            )
                        }
                        setSongsDataSource(music)
                    }
                    R.id.default_sorting -> applySortingToMusic(GoConstants.DEFAULT_SORTING)
                    R.id.descending_sorting -> applySortingToMusic(GoConstants.DESCENDING_SORTING)
                    R.id.ascending_sorting -> applySortingToMusic(GoConstants.ASCENDING_SORTING)
                    R.id.track_sorting -> applySortingToMusic(GoConstants.TRACK_SORTING)
                    R.id.track_sorting_inv -> applySortingToMusic(GoConstants.TRACK_SORTING_INVERTED)
                }
                return@setOnMenuItemClickListener true
            }
        }
    }

    private fun applySortingToMusic(order: Int) {
        val selectedList = if (sLaunchedByFolderView) {
            mMusicViewModel.deviceMusicByFolder?.get(mSelectedArtistOrFolder)
        } else {
            mMusicViewModel.deviceMusicByAlbum?.get(mSelectedArtistOrFolder)
        }
        if (!sWasShuffling.first) {
            mSongsList = ListsHelper.getSortedMusicList(
                order,
                selectedList?.toMutableList()
            )
            setSongsDataSource(mSongsList)
        }
    }

    private fun setupAlbumsContainer() {

        mDetailsFragmentBinding.selectedAlbumContainer.setOnClickListener {
            mAlbumsRecyclerViewLayoutManager.scrollToPositionWithOffset(
                mSelectedAlbumPosition,
                0
            )
        }

        mDetailsFragmentBinding.selectedAlbum.isSelected = true

        updateSelectedAlbumTitle()

        setAlbumsDataSource(mSelectedArtistAlbums)

        mDetailsFragmentBinding.detailsToolbar.subtitle = getString(
            R.string.artist_info,
            mSelectedArtistAlbums?.size,
            mSongsList?.size
        )

        mDetailsFragmentBinding.albumsRv.run {

            setHasFixedSize(true)

            setup {

                withDataSource(mSelectedAlbumsDataSource)

                mAlbumsRecyclerViewLayoutManager = layoutManager as LinearLayoutManager

                withItem<Album, AlbumsViewHolder>(R.layout.album_item) {

                    onBind(::AlbumsViewHolder) { _, item ->
                        // AlbumsViewHolder is `this` here

                        val cardView = itemView as MaterialCardView

                        album.text = item.title

                        year.text = item.year
                        totalDuration.text = item.totalDuration.toFormattedDuration(
                            isAlbum = true,
                            isSeekBar = false
                        )

                        cardView.strokeWidth = if (mSelectedAlbum?.title == item.title) {
                            resources.getDimensionPixelSize(R.dimen.album_stroke)
                        } else {
                            0
                        }

                        if (sIsCovers) {
                            val request = ImageRequest.Builder(context)
                                        .data(item.music?.get(0)?.albumId?.getCoverFromURI())
                                        .target(
                                                onSuccess = { result ->
                                                    // Handle the successful result.
                                                    imageView.load(result)
                                                },
                                                onError = {
                                                    imageView.load(R.drawable.album_art)
                                                }
                                        )
                                        .build()

                                mLoadCoverIoScope.launch {
                                    withContext(mLoadCoverIoDispatcher) {
                                        mImageLoader.enqueue(request)
                                    }
                                }
                            }
                    }

                    onClick { index ->

                        if (index != mSelectedAlbumPosition) {

                            adapter?.let { albumsAdapter ->

                                albumsAdapter.notifyItemChanged(
                                    mSelectedAlbumPosition
                                )

                                albumsAdapter.notifyItemChanged(index)

                                mSelectedAlbum = item
                                mSelectedAlbumPosition = index

                                updateSelectedAlbumTitle()

                                swapAlbum(item.title, item.music)
                            }
                        } else {
                            mUIControlInterface.onSongSelected(
                                item.music?.get(0),
                                item.music,
                                mLaunchedBy
                            )
                        }
                    }
                }
            }
            if (mSelectedAlbumPosition != -1 || mSelectedAlbumPosition != 0) {
                mAlbumsRecyclerViewLayoutManager.scrollToPositionWithOffset(
                    mSelectedAlbumPosition,
                    0
                )
            }
        }
    }

    fun hasToUpdate(selectedArtistOrFolder: String?) =
        selectedArtistOrFolder != mSelectedArtistOrFolder

    fun tryToSnapToAlbumPosition(snapPosition: Int) {
        if (sLaunchedByArtistView && snapPosition != -1) {
            mDetailsFragmentBinding.albumsRv.smoothSnapToPosition(
                snapPosition
            )
        }
    }

    fun onDisableShuffle(isShuffleMode: Boolean) {
        if (sWasShuffling.first && !isShuffleMode) {
            mSongsSorting = GoConstants.TRACK_SORTING
            if (sWasShuffling.first && mSelectedAlbum?.title == sWasShuffling.second) {
                updateSorting(mSongsSorting)
            }
            sWasShuffling = Pair(false, null)
        }
    }

    private fun updateSelectedAlbumTitle() {
        mDetailsFragmentBinding.selectedAlbum.text = mSelectedAlbum?.title
        mDetailsFragmentBinding.albumYearDuration.text = getString(
            R.string.year_and_duration,
            mSelectedAlbum?.totalDuration?.toFormattedDuration(isAlbum = true, isSeekBar = false),
            mSelectedAlbum?.year
        )
    }

    private fun swapAlbum(title: String?, songs: MutableList<Music>?) {
        mSongsSorting = if (sWasShuffling.first && sWasShuffling.second == title) {
            GoConstants.SHUFFLE_SORTING
        } else {
            GoConstants.TRACK_SORTING
        }
        mDetailsFragmentBinding.sortButton.setImageResource(
            ThemeHelper.resolveSortAlbumSongsIcon(
                mSongsSorting
            )
        )
        setSongsDataSource(songs)
        mDetailsFragmentBinding.songsRv.scrollToPosition(0)
    }

    companion object {

        private const val TAG_ARTIST_FOLDER = "SELECTED_ARTIST_FOLDER"
        private const val TAG_IS_FOLDER = "IS_FOLDER"
        private const val TAG_SELECTED_ALBUM_POSITION = "SELECTED_ALBUM_POSITION"
        private const val TAG_IS_SHUFFLING = "IS_SHUFFLING"
        private const val TAG_SHUFFLED_ALBUM = "SHUFFLED_ALBUM"

        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @return A new instance of fragment DetailsFragment.
         */
        @JvmStatic
        fun newInstance(
            selectedArtistOrFolder: String?,
            launchedBy: String,
            playedAlbumPosition: Int,
            isShuffleMode: Pair<Boolean, String?>,
        ) =
            DetailsFragment().apply {
                arguments = bundleOf(
                    TAG_ARTIST_FOLDER to selectedArtistOrFolder,
                    TAG_IS_FOLDER to launchedBy,
                    TAG_SELECTED_ALBUM_POSITION to playedAlbumPosition,
                    TAG_IS_SHUFFLING to isShuffleMode.first,
                    TAG_SHUFFLED_ALBUM to isShuffleMode.second
                )
            }
    }
}
