
fun showMediaPreview(item: MediaItem) {
    _previewMediaItem.value = item
    val peeked = repository.peekCachedDetails(item)
    if (peeked != null) {
        _previewEnrichedDetails.value = peeked
        _isPreviewLoading.value = false
        return
    }

    _previewEnrichedDetails.value = null
    _isPreviewLoading.value = true

    previewJob?.cancel()
    previewJob = viewModelScope.launch {
        delay(120L)
        try {
            val apiKey = tmdbSessionStore.getSavedApiKey()
            val isArabic = _userSettings.value.language.equals(
                "Arabic",
                ignoreCase = true
            )
            val details = repository.getOrFetchEnrichedMediaDetails(
                item = item,
                tmdbApiKey = apiKey,
                isArabic = isArabic,
                accountOverride = _activeAccount.value
            )
            if (_previewMediaItem.value?.id == item.id) {
                _previewEnrichedDetails.value = details
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                e.printStackTrace()
            }
        } finally {
            _isPreviewLoading.value = false
        }
    }
}

fun showMediaDetails(item: MediaItem) {
    dismissMediaPreview()
    _fullDetailsMediaItem.value = item

    if (item.type == MediaType.SERIES) {
        loadSeriesDetails(item)
    }

    val peeked = repository.peekCachedDetails(item)
    if (peeked != null) {
        _fullDetailsEnriched.value = peeked
        _isFullDetailsLoading.value = false
    } else {
        _fullDetailsEnriched.value = null
        _isFullDetailsLoading.value = true
    }

    fullDetailsJob?.cancel()
    fullDetailsJob = viewModelScope.launch {
        try {
            val apiKey = tmdbSessionStore.getSavedApiKey()
            val isArabic = _userSettings.value.language.equals(
                "Arabic",
                ignoreCase = true
            )
            val details = repository.getOrFetchEnrichedMediaDetails(
                item = item,
                tmdbApiKey = apiKey,
                isArabic = isArabic,
                accountOverride = _activeAccount.value
            )
            if (_fullDetailsMediaItem.value?.id == item.id) {
                _fullDetailsEnriched.value = details
                if (
                    item.type == MediaType.SERIES &&
                    details.seasons.isNotEmpty()
                ) {
                    _currentSeriesDetails.value = SeriesDetails(
                        seriesId = item.streamId,
                        name = details.title,
                        cover = details.posterUrl,
                        backdrop = details.backdropUrl,
                        plot = details.plot,
                        cast = details.cast.joinToString(", ") { it.name },
                        director = details.director,
                        genre = details.genres.joinToString(", "),
                        releaseDate = details.releaseDate,
                        rating = details.rating,
                        seasons = details.seasons,
                        quality = details.quality,
                        youtubeTrailer = details.youtubeTrailer,
                        tmdbId = details.tmdbId
                    )
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                e.printStackTrace()
            }
        } finally {
            _isFullDetailsLoading.value = false
        }
    }
}
