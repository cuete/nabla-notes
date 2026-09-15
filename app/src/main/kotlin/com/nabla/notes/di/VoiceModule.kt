package com.nabla.notes.di

import com.nabla.notes.repository.DictationRepository
import com.nabla.voice.TranscriptStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the :voice module's persistence seam to this app's own DataStore-backed repository. */
@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceModule {

    @Binds
    @Singleton
    abstract fun bindTranscriptStore(impl: DictationRepository): TranscriptStore
}
