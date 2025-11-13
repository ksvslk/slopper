package com.indrek.slopper.di

import android.content.Context
import android.content.SharedPreferences
import com.indrek.slopper.C.PREFS_NAME
import com.indrek.slopper.SlopperApplication
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext app: Context): SharedPreferences {
        return app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * This allows Hilt to inject the SlopperApplication instance, which
     * we need to access the TrustedTimeClient.
     */
    @Provides
    @Singleton
    fun provideSlopperApplication(@ApplicationContext app: Context): SlopperApplication {
        return app as SlopperApplication
    }
}