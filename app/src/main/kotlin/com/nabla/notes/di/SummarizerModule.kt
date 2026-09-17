package com.nabla.notes.di

import com.nabla.notes.summarizer.OpenClawSummarizer
import com.nabla.notes.summarizer.Summarizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the provider-agnostic Summarizer seam to its only implementation today. Swapping
 * providers later (e.g. a Claude-API-direct one) is changing this one line, not touching
 * DictationViewModel — see Summarizer's class doc.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SummarizerModule {

    @Binds
    @Singleton
    abstract fun bindSummarizer(impl: OpenClawSummarizer): Summarizer
}
