/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import scala.concurrent.duration.Duration
import com.typesafe.config._
import akka.routing._
import akka.japi.Util.immutableSeq
import java.util.concurrent.{ TimeUnit }
import akka.util.WildcardTree
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

/**
 * This class represents deployment configuration for a given actor path. It is
 * marked final in order to guarantee stable merge semantics (i.e. what
 * overrides what in case multiple configuration sources are available) and is
 * fully extensible via its Scope argument, and by the fact that an arbitrary
 * Config section can be passed along with it (which will be merged when merging
 * two Deploys).
 *
 * The path field is used only when inserting the Deploy into a deployer and
 * not needed when just doing deploy-as-you-go:
 *
 * {{{
 * context.actorOf(someProps, "someName", Deploy(scope = RemoteScope("someOtherNodeName")))
 * }}}
 */
@SerialVersionUID(1L)
final case class Deploy(
  path: String = "",
  config: Config = ConfigFactory.empty,
  routerConfig: RouterConfig = NoRouter,
  scope: Scope = NoScopeGiven) {

  /**
   * Java API to create a Deploy with the given RouterConfig
   */
  def this(routing: RouterConfig) = this("", ConfigFactory.empty, routing)

  /**
   * Java API to create a Deploy with the given RouterConfig with Scope
   */
  def this(routing: RouterConfig, scope: Scope) = this("", ConfigFactory.empty, routing, scope)

  /**
   * Java API to create a Deploy with the given Scope
   */
  def this(scope: Scope) = this("", ConfigFactory.empty, NoRouter, scope)

  /**
   * Do a merge between this and the other Deploy, where values from “this” take
   * precedence. The “path” of the other Deploy is not taken into account. All
   * other members are merged using ``<X>.withFallback(other.<X>)``.
   */
  def withFallback(other: Deploy): Deploy =
    Deploy(path, config.withFallback(other.config), routerConfig.withFallback(other.routerConfig), scope.withFallback(other.scope))
}

/**
 * The scope of a [[akka.actor.Deploy]] serves two purposes: as a marker for
 * pattern matching the “scope” (i.e. local/remote/cluster) as well as for
 * extending the information carried by the final Deploy class. Scopes can be
 * used in conjunction with a custom [[akka.actor.ActorRefProvider]], making
 * Akka actors fully extensible.
 */
trait Scope {
  /**
   * When merging [[akka.actor.Deploy]] instances using ``withFallback()`` on
   * the left one, this is propagated to “merging” scopes in the same way.
   * The setup is biased towards preferring the callee over the argument, i.e.
   * ``a.withFallback(b)`` is called expecting that ``a`` should in general take
   * precedence.
   */
  def withFallback(other: Scope): Scope
}

@SerialVersionUID(1L)
abstract class LocalScope extends Scope

/**
 * The Local Scope is the default one, which is assumed on all deployments
 * which do not set a different scope. It is also the only scope handled by
 * the LocalActorRefProvider.
 */
case object LocalScope extends LocalScope {
  /**
   * Java API: get the singleton instance
   */
  def getInstance = this

  def withFallback(other: Scope): Scope = this
}

/**
 * This is the default value and as such allows overrides.
 */
@SerialVersionUID(1L)
abstract class NoScopeGiven extends Scope
case object NoScopeGiven extends NoScopeGiven {
  def withFallback(other: Scope): Scope = other

  /**
   * Java API: get the singleton instance
   */
  def getInstance = this
}

/**
 * Deployer maps actor paths to actor deployments.
 */
private[akka] class Deployer(val settings: ActorSystem.Settings, val dynamicAccess: DynamicAccess) {

  import scala.collection.JavaConverters._

  private val deployments = new AtomicReference(WildcardTree[Deploy]())
  private val config = settings.config.getConfig("akka.actor.deployment")
  protected val default = config.getConfig("default")

  config.root.asScala flatMap {
    case ("default", _)             ⇒ None
    case (key, value: ConfigObject) ⇒ parseConfig(key, value.toConfig)
    case _                          ⇒ None
  } foreach deploy

  def lookup(path: ActorPath): Option[Deploy] = lookup(path.elements.drop(1).iterator)

  def lookup(path: Iterable[String]): Option[Deploy] = lookup(path.iterator)

  def lookup(path: Iterator[String]): Option[Deploy] = deployments.get().find(path).data

  def deploy(d: Deploy): Unit = {
    @tailrec def add(path: Array[String], d: Deploy, w: WildcardTree[Deploy] = deployments.get): Unit =
      if (!deployments.compareAndSet(w, w.insert(path.iterator, d))) add(path, d)

    add(d.path.split("/").drop(1), d)
  }

  def parseConfig(key: String, config: Config): Option[Deploy] = {
    val deployment = config.withFallback(default)
    val router = createRouterConfig(deployment.getString("router"), key, config, deployment)
    Some(Deploy(key, deployment, router, NoScopeGiven))
  }

  /**
   * Factory method for creating `RouterConfig`
   * @param routerType the configured name of the router, or FQCN
   * @param key the full configuration key of the deployment section
   * @param config the user defined config of the deployment, without defaults
   * @param deployment the deployment config, with defaults
   */
  protected def createRouterConfig(routerType: String, key: String, config: Config, deployment: Config): RouterConfig = {
    val routees = immutableSeq(deployment.getStringList("routees.paths"))
    val nrOfInstances = deployment.getInt("nr-of-instances")
    val resizer = if (config.hasPath("resizer")) Some(DefaultResizer(deployment.getConfig("resizer"))) else None

    routerType match {
      case "from-code"        ⇒ NoRouter
      case "round-robin"      ⇒ RoundRobinRouter(nrOfInstances, routees, resizer)
      case "random"           ⇒ RandomRouter(nrOfInstances, routees, resizer)
      case "smallest-mailbox" ⇒ SmallestMailboxRouter(nrOfInstances, routees, resizer)
      case "broadcast"        ⇒ BroadcastRouter(nrOfInstances, routees, resizer)
      case "scatter-gather" ⇒
        val within = Duration(deployment.getMilliseconds("within"), TimeUnit.MILLISECONDS)
        ScatterGatherFirstCompletedRouter(nrOfInstances, routees, within, resizer)
      case "consistent-hashing" ⇒
        val vnodes = deployment.getInt("virtual-nodes-factor")
        ConsistentHashingRouter(nrOfInstances, routees, resizer, virtualNodesFactor = vnodes)
      case fqn ⇒
        val args = List(classOf[Config] -> deployment)
        dynamicAccess.createInstanceFor[RouterConfig](fqn, args).recover({
          case exception ⇒ throw new IllegalArgumentException(
            ("Cannot instantiate router [%s], defined in [%s], " +
              "make sure it extends [akka.routing.RouterConfig] and has constructor with " +
              "[com.typesafe.config.Config] parameter")
              .format(fqn, key), exception)
        }).get
    }
  }

}
