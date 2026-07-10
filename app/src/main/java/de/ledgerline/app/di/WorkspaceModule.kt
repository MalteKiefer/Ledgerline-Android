package de.ledgerline.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.ledgerline.app.data.DownloadFileImpl
import de.ledgerline.app.data.LoadWorkspaceImpl
import de.ledgerline.app.data.MutateWorkspaceImpl
import de.ledgerline.app.data.UploadFileImpl
import de.ledgerline.app.domain.usecase.DownloadFile
import de.ledgerline.app.domain.usecase.LoadWorkspace
import de.ledgerline.app.domain.usecase.MutateWorkspace
import de.ledgerline.app.domain.usecase.UploadFile

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkspaceModule {
    @Binds abstract fun bindLoadWorkspace(impl: LoadWorkspaceImpl): LoadWorkspace
    @Binds abstract fun bindMutateWorkspace(impl: MutateWorkspaceImpl): MutateWorkspace
    @Binds abstract fun bindUploadFile(impl: UploadFileImpl): UploadFile
    @Binds abstract fun bindDownloadFile(impl: DownloadFileImpl): DownloadFile
}
