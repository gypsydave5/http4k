package org.http4k.contract

import org.http4k.contract.ContractRouterMatch.MatchedWithoutHandler
import org.http4k.contract.ContractRouterMatch.MatchingHandler
import org.http4k.contract.ContractRouterMatch.MethodNotMatched
import org.http4k.contract.ContractRouterMatch.Unmatched
import org.http4k.core.Filter
import org.http4k.core.Method.GET
import org.http4k.core.NoOp
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.UriTemplate
import org.http4k.core.then
import org.http4k.filter.ServerFilters.CatchLensFailure
import org.http4k.routing.All
import org.http4k.routing.RequestWithContext
import org.http4k.routing.ResponseWithContext
import org.http4k.routing.RouteMatcher
import org.http4k.routing.Router
import org.http4k.routing.RouterDescription.Companion.unavailable
import org.http4k.routing.RoutingMatch
import org.http4k.routing.and
import org.http4k.security.NoSecurity.filter
import org.http4k.security.Security

data class ContractRouteMatcher(
    private val renderer: ContractRenderer,
    private val security: Security?,
    private val tags: Set<Tag>,
    private val descriptionSecurity: Security?,
    private val descriptionPath: String,
    private val preFlightExtraction: PreFlightExtraction,
    private val routes: List<ContractRoute> = emptyList(),
    private val rootAsString: String = "",
    private val preSecurityFilter: Filter = Filter.NoOp,
    private val postSecurityFilter: Filter = Filter.NoOp,
    private val includeDescriptionRoute: Boolean = false,
    private val webhooks: Map<String, List<WebCallback>> = emptyMap(),
    private val router: Router = All,
) : RouteMatcher<Response, Filter> {
    private val contractRoot = PathSegments(rootAsString)

    private val notFound = preSecurityFilter
        .then(security?.filter ?: Filter.NoOp)
        .then(postSecurityFilter)
        .then { renderer.notFound() }

    override fun match(request: Request): RoutingMatch<Response> {
        val m = internalMatch(request)
        return RoutingMatch(
            m.priority,
            m.description,
            filter.then(
                when (m) {
                    is MatchingHandler -> m
                    is MatchedWithoutHandler -> notFound
                    is MethodNotMatched -> notFound
                    is Unmatched -> notFound
                }
            )
        )
    }

    private fun internalMatch(request: Request): ContractRouterMatch {
        val unmatched: ContractRouterMatch = Unmatched

        return if (request.isIn(contractRoot)) {
            routers.fold(unmatched) { memo, (routeFilter, router) ->
                when (memo) {
                    is MatchingHandler -> memo
                    else -> when (val matchResult = router.match(request)) {
                        is MatchingHandler -> MatchingHandler(unavailable, routeFilter.then(matchResult))
                        else -> minOf(memo, matchResult)
                    }
                }
            }
        } else unmatched
    }

    override fun withBasePath(prefix: String) = copy(rootAsString = prefix + rootAsString)

    override fun withRouter(other: Router): RouteMatcher<Response, Filter> = copy(router = router.and(other))

    override fun withFilter(new: Filter): RouteMatcher<Response, Filter> =
        copy(preSecurityFilter = new.then(preSecurityFilter))

    val description =
        routes.joinToString("\n") { it.toRouter(PathSegments("$rootAsString/$it$descriptionPath")).description }

    private val descriptionRoute =
        ContractRouteSpec0({ PathSegments("$it$descriptionPath") }, RouteMeta(operationId = "description"))
            .let {
                val extra = listOfNotNull(
                    when {
                        includeDescriptionRoute -> it bindContract GET to { _ -> Response(Status.OK) }
                        else -> null
                    })
                it bindContract GET to { _ ->
                    renderer.description(
                        contractRoot,
                        security,
                        (routes + extra).filter { route -> route.meta.described },
                        tags,
                        webhooks
                    )
                }
            }

    private val routers = routes
        .map {
            identify(it)
                .then(preSecurityFilter)
                .then(it.meta.security?.filter ?: security?.filter ?: Filter.NoOp)
                .then(postSecurityFilter)
                .then(CatchLensFailure(renderer::badRequest))
                .then(PreFlightExtractionFilter(it.meta, preFlightExtraction)) to it.toRouter(contractRoot)
        } + (identify(descriptionRoute)
        .then(preSecurityFilter)
        .then(descriptionSecurity?.filter ?: Filter.NoOp)
        .then(postSecurityFilter) to descriptionRoute.toRouter(contractRoot))

    override fun toString() = contractRoot.toString() + "\n" + routes.joinToString("\n") { it.toString() }

    private fun identify(route: ContractRoute) =
        route.describeFor(contractRoot).let { routeIdentity ->
            Filter { next ->
                {
                    val xUriTemplate = UriTemplate.from(routeIdentity.ifEmpty { "/" })
                    ResponseWithContext(next(RequestWithContext(it, xUriTemplate)), xUriTemplate)
                }
            }
        }
}
