package coursier

import java.net.MalformedURLException

import coursier.core.Authentication
import coursier.ivy.IvyRepository
import coursier.util.Parse

import scalaz._, Scalaz._

object CacheParse {

  def repository(s: String): Validation[String, Repository] =
    if (s == "ivy2local" || s == "ivy2Local")
      Cache.ivy2Local.success
    else if (s == "ivy2cache" || s == "ivy2Cache")
      Cache.ivy2Cache.success
    else {
      val repo = Parse.repository(s)

      val url = repo.map {
        case m: MavenRepository =>
          m.root
        case i: IvyRepository =>
          // FIXME We're not handling metadataPattern here
          i.pattern.chunks.takeWhile {
            case _: coursier.ivy.Pattern.Chunk.Const => true
            case _ => false
          }.map(_.string).mkString
        case r =>
          sys.error(s"Unrecognized repository: $r")
      }

      val validatedUrl = try {
        url.map(Cache.url).validation
      } catch {
        case e: MalformedURLException =>
          ("Error parsing URL " + url + Option(e.getMessage).fold("")(" (" + _ + ")")).failure
      }

      validatedUrl.flatMap { url =>
        Option(url.getUserInfo) match {
          case None =>
            repo.validation
          case Some(userInfo) =>
            userInfo.split(":", 2) match {
              case Array(user, password) =>
                val baseUrl = new java.net.URL(
                  url.getProtocol,
                  url.getHost,
                  url.getPort,
                  url.getFile
                ).toString

                repo.validation.map {
                  case m: MavenRepository =>
                    m.copy(
                      root = baseUrl,
                      authentication = Some(Authentication(user, password))
                    )
                  case i: IvyRepository =>
                    i.copy(
                      pattern = coursier.ivy.Pattern(
                        coursier.ivy.Pattern.Chunk.Const(baseUrl) +: i.pattern.chunks.dropWhile {
                          case _: coursier.ivy.Pattern.Chunk.Const => true
                          case _ => false
                        }
                      ),
                      authentication = Some(Authentication(user, password))
                    )
                  case r =>
                    sys.error(s"Unrecognized repository: $r")
                }

              case _ =>
                s"No password found in user info of URL $url".failure
            }
        }
      }
    }

  def repositories(l: Seq[String]): ValidationNel[String, Seq[Repository]] =
    l.toVector.traverseU { s =>
      repository(s).leftMap(_.wrapNel)
    }

  def cachePolicies(s: String): ValidationNel[String, Seq[CachePolicy]] =
    s.split(',').toVector.traverseU {
      case "offline" =>
        Seq(CachePolicy.LocalOnly).successNel
      case "update-local-changing" =>
        Seq(CachePolicy.LocalUpdateChanging).successNel
      case "update-local" =>
        Seq(CachePolicy.LocalUpdate).successNel
      case "update-changing" =>
        Seq(CachePolicy.UpdateChanging).successNel
      case "update" =>
        Seq(CachePolicy.Update).successNel
      case "missing" =>
        Seq(CachePolicy.FetchMissing).successNel
      case "force" =>
        Seq(CachePolicy.ForceDownload).successNel
      case "default" =>
        Seq(CachePolicy.LocalOnly, CachePolicy.FetchMissing).successNel
      case other =>
        s"Unrecognized mode: $other".failureNel
    }.map(_.flatten)

}
