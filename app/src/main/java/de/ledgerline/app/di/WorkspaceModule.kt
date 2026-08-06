package de.ledgerline.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.ledgerline.app.data.DownloadFileImpl
import de.ledgerline.app.data.FileBlobRepository
import de.ledgerline.app.data.ForceLogoutImpl
import de.ledgerline.app.data.FilesUsageImpl
import de.ledgerline.app.data.ImportFileImpl
import de.ledgerline.app.data.LoadWorkspaceImpl
import de.ledgerline.app.data.MutateWorkspaceImpl
import de.ledgerline.app.data.UploadFileImpl
import de.ledgerline.app.domain.usecase.DownloadFile
import de.ledgerline.app.domain.usecase.FileBlobs
import de.ledgerline.app.domain.usecase.FilesUsage
import de.ledgerline.app.domain.usecase.ForceLogout
import de.ledgerline.app.domain.usecase.ImportFile
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
    @Binds abstract fun bindFileBlobs(impl: FileBlobRepository): FileBlobs
    @Binds abstract fun bindFilesUsage(impl: FilesUsageImpl): FilesUsage
    @Binds abstract fun bindForceLogout(impl: ForceLogoutImpl): ForceLogout
    @Binds abstract fun bindImportFile(impl: ImportFileImpl): ImportFile
    @Binds abstract fun bindFileSharing(impl: de.ledgerline.app.data.ShareRepository): de.ledgerline.app.data.FileSharing
}
