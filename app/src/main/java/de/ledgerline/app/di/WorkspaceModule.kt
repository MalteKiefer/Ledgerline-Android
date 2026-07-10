package de.ledgerline.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.ledgerline.app.data.LoadWorkspaceImpl
import de.ledgerline.app.domain.usecase.LoadWorkspace

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkspaceModule {
    @Binds abstract fun bindLoadWorkspace(impl: LoadWorkspaceImpl): LoadWorkspace
}
