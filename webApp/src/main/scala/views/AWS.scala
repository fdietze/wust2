package wust.webApp.views

import java.util.concurrent.TimeUnit

import cats.effect.IO
import monix.eval.Task
import monix.execution.Ack
import monix.reactive.Observable
import monix.reactive.subjects.ReplaySubject
import org.scalajs.dom
import org.scalajs.dom.FormData
import org.scalajs.dom.raw.XMLHttpRequest
import outwatch.dom.VNode
import wust.webApp.state.{GlobalState, UploadingFile}
import outwatch.dom._
import outwatch.dom.dsl._
import rx._
import sha256.Sha256
import sun.swing.FilePane.FileChooserUIAccessor
import wust.api.{AuthUser, FileUploadConfiguration, ApiEvent}
import wust.graph._
import wust.ids._
import wust.webApp.Client
import wust.webApp.jsdom.FileReaderOps
import wust.webApp.outwatchHelpers._

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.FiniteDuration
import scala.scalajs.js
import scala.util.{Failure, Success}

object AWS {
  case class UploadableFile(file: dom.File, dataUrl: String, uploadKey: Task[Option[String]])

  def upload(state: GlobalState, file: dom.File): Option[UploadableFile] = {
    state.user.now match {
      case _: AuthUser.Real => ()
      case _ =>
        UI.toast(s"You need to register an account before you can upload anything.", level = UI.ToastLevel.Info)
        return None
    }

    if(file.size > FileUploadConfiguration.maxUploadBytesPerFile) {
      UI.toast(s"The file '${file.name}' is bigger than the allowed limit of ${FileUploadConfiguration.maxUploadBytesPerFile / 1024 / 1024} MB.", level = UI.ToastLevel.Warning)
      return None
    }

    val uploadedKey = Task.deferFuture {
      val config = for {
        fileContent <- FileReaderOps.readAsText(file) // TODO: is that even correct for the content of binary files?
        fileContentDigest = Sha256.sha256(fileContent)
        config <- Client.api.fileUploadConfiguration(fileContentDigest, file.size.toInt, file.name, file.`type`)
      } yield config

      val promise = Promise[Option[String]]
      config.onComplete {
        case Success(config: FileUploadConfiguration.UploadToken) =>
          val xhr = new XMLHttpRequest()
          xhr.open("POST", config.baseUrl, true)

          xhr.onload = { _ =>
            if (xhr.status == 200 || xhr.status == 201 || xhr.status == 204) {
              promise success Some(config.key)
              UI.toast("File was Successfully uploaded", level = UI.ToastLevel.Success)
            } else {
              promise success None
              UI.toast("Failed to upload file", level = UI.ToastLevel.Error)
            }
          }
          xhr.onerror = { e =>
            promise success None
            UI.toast("Error while uploading file", level = UI.ToastLevel.Error)
          }

          val formData = new FormData()
          formData.append("key", s"${config.key}")
          formData.append("x-amz-credential", config.credential)
          formData.append("x-amz-algorithm", config.algorithm)
          formData.append("cache-control", config.cacheControl)
          formData.append("content-type", file.`type`)
          formData.append("content-disposition", config.contentDisposition)
          formData.append("acl", config.acl)
          // formData.append("success_action_redirect", dom.window.location.toString)
          formData.append("policy", config.policyBase64)
          formData.append("x-amz-signature", config.signature)
          formData.append("x-amz-date", config.date)
          formData.append("file", file)

          xhr.send(formData)

        case Success(FileUploadConfiguration.KeyExists(key)) =>
          UI.toast("File was Successfully uploaded", level = UI.ToastLevel.Success)
          promise success Some(key)
        case Success(FileUploadConfiguration.QuotaExceeded) =>
          promise success None
          UI.toast(s"Sorry, you have exceeded your file-upload quota. You only have ${FileUploadConfiguration.maxUploadBytesPerUser / 1024 / 1024} MB. Click here to check your uploaded files in your user settings.", click = () => state.urlConfig.update(_.focus(View.UserSettings)))
        case Success(FileUploadConfiguration.ServiceUnavailable) =>
          promise success None
          UI.toast("Sorry, the file-upload service is currently unavailable. Please try again later!")
        case Failure(t)                       =>
          promise success None
          scribe.warn("Cannot get file upload configuration", t)
          UI.toast("Sorry, the file-upload service is currently unreachable. Please try again later!")
      }

      promise.future
    }

    val url = dom.URL.createObjectURL(file)
    Some(UploadableFile(file = file, dataUrl = url, uploadKey = uploadedKey))
  }

  def uploadFileAndCreateNode(state: GlobalState, str: String, uploadFile: AWS.UploadableFile, extraChanges: NodeId => GraphChanges = _ => GraphChanges.empty): Future[Ack] = {

    val (initialChanges, observableChanges) = uploadFileAndCreateNodeChanges(state, str, uploadFile, extraChanges)

    // propagate locally with loading icon
    val ack = state.eventProcessor.localEvents.onNext(ApiEvent.NewGraphChanges.forPrivate(state.user.now.toNode, initialChanges.withAuthor(state.user.now.id)))

    observableChanges.subscribe(state.eventProcessor.changes)

    ack
  }

  def uploadFileAndCreateNodeChanges(state: GlobalState, str: String, uploadFile: AWS.UploadableFile, extraChanges: NodeId => GraphChanges = _ => GraphChanges.empty): (GraphChanges, Observable[GraphChanges]) = {
    def toGraphChanges(node: Node) = GraphChanges.addNode(node).merge(extraChanges(node.id))

    val fileNodeData = NodeData.File(key = "", fileName = uploadFile.file.name, contentType = uploadFile.file.`type`, description = str) // TODO: empty string for signaling pending fileupload
    val fileNode = Node.Content(fileNodeData, NodeRole.Message)

    val initialChanges = toGraphChanges(fileNode)

    //TODO: there is probably a better way to implement this...
    val observableChanges = ReplaySubject.createLimited[GraphChanges](1)
    var uploadTask: Task[Unit] = null
    uploadTask = Task.defer{
      state.uploadingFiles.update(_ ++ Map(fileNode.id -> UploadingFile.Waiting(uploadFile.dataUrl)))

      uploadFile.uploadKey.map {
        case Some(key) =>
          state.uploadingFiles.update(_ - fileNode.id)
          observableChanges.onNext(toGraphChanges(fileNode.copy(data = fileNodeData.copy(key = key))))
          ()
        case None      =>
          state.uploadingFiles.update(_ ++ Map(fileNode.id -> UploadingFile.Error(uploadFile.dataUrl, uploadTask)))
          ()
      }
    }

    uploadTask.runAsyncAndForget

    initialChanges -> observableChanges
  }
}
