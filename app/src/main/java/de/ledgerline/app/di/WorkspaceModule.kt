package de.ledgerline.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.ledgerline.app.data.DownloadFileImpl
import de.ledgerline.app.data.FileBlobRepository
import de.ledgerline.app.data.ForceLogoutImpl
import de.ledgerline.app.data.FilesUsageImpl
import de.ledgerline.app.data.GalleryBlobRepository
import de.ledgerline.app.data.GalleryUsageImpl
import de.ledgerline.app.data.LoadGalleryImpl
import de.ledgerline.app.data.LoadWorkspaceImpl
import de.ledgerline.app.data.MutateGalleryImpl
import de.ledgerline.app.data.MutateWorkspaceImpl
import de.ledgerline.app.data.UploadFileImpl
import de.ledgerline.app.domain.usecase.DownloadFile
import de.ledgerline.app.domain.usecase.FileBlobs
import de.ledgerline.app.domain.usecase.FilesUsage
import de.ledgerline.app.domain.usecase.ForceLogout
import de.ledgerline.app.domain.usecase.GalleryBlobs
import de.ledgerline.app.domain.usecase.GalleryUploadApi
import de.ledgerline.app.domain.usecase.GalleryUsage
import de.ledgerline.app.domain.usecase.LoadGallery
import de.ledgerline.app.domain.usecase.LoadWorkspace
import de.ledgerline.app.domain.usecase.MutateGallery
import de.ledgerline.app.domain.usecase.MutateWorkspace
import de.ledgerline.app.domain.usecase.UploadFile

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkspaceModule {
    @Binds abstract fun bindLoadWorkspace(impl: LoadWorkspaceImpl): LoadWorkspace
    @Binds abstract fun bindMutateWorkspace(impl: MutateWorkspaceImpl): MutateWorkspace
    @Binds abstract fun bindMutateGallery(impl: MutateGalleryImpl): MutateGallery
    @Binds abstract fun bindUploadFile(impl: UploadFileImpl): UploadFile
    @Binds abstract fun bindDownloadFile(impl: DownloadFileImpl): DownloadFile
    @Binds abstract fun bindFileBlobs(impl: FileBlobRepository): FileBlobs
    @Binds abstract fun bindFilesUsage(impl: FilesUsageImpl): FilesUsage
    @Binds abstract fun bindLoadGallery(impl: LoadGalleryImpl): LoadGallery
    @Binds abstract fun bindGalleryBlobs(impl: GalleryBlobRepository): GalleryBlobs
    @Binds abstract fun bindGalleryUploadApi(impl: GalleryBlobRepository): GalleryUploadApi
    @Binds abstract fun bindGalleryUsage(impl: GalleryUsageImpl): GalleryUsage
    @Binds abstract fun bindForceLogout(impl: ForceLogoutImpl): ForceLogout
}
