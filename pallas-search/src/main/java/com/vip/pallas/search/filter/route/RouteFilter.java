package com.vip.pallas.search.filter.route;

import static com.vip.pallas.search.http.HttpCode.HTTP_FORBIDDEN;
import static com.vip.pallas.search.http.HttpCode.HTTP_SERVICE_UNAVAILABLE;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vip.pallas.search.exception.HttpCodeErrorPallasException;
import com.vip.pallas.search.filter.base.AbstractFilter;
import com.vip.pallas.search.filter.base.AbstractFilterContext;
import com.vip.pallas.search.filter.circuitbreaker.CircuitBreakerService;
import com.vip.pallas.search.filter.common.SessionContext;
import com.vip.pallas.search.http.PallasRequest;
import com.vip.pallas.search.model.IndexRouting;
import com.vip.pallas.search.model.IndexRoutingTargetGroup;
import com.vip.pallas.search.model.SearchAuthorization;
import com.vip.pallas.search.model.ServiceInfo;
import com.vip.pallas.search.model.ShardGroup;
import com.vip.pallas.search.monitor.GaugeMonitorService;
import com.vip.pallas.search.service.PallasCacheFactory;
import com.vip.pallas.search.utils.IPMatcher;
import com.vip.pallas.search.utils.PallasSearchProperties;

import io.netty.util.internal.InternalThreadLocalMap;

public class RouteFilter extends AbstractFilter {
	public static String DEFAULT_NAME = PRE_FILTER_NAME + RouteFilter.class.getSimpleName().toUpperCase();
	static String DEFAULT_AUTHORIZATION_PRIVILEGE = PallasSearchProperties.PALLAS_SEARCH_DEFAULT_AUTHORICATION;

	private static final String PARAM_TYPE_HEADER = "header";
	private static final String X_PALLAS_SEARCH_CLIENT_IP = "X-PALLAS-SEARCH-CLIENT-IP";

	private static final String EXPR_OP_EQUAL = "=";
	private static final String EXPR_OP_MASK = "ip_mask";

	private static final String CONDITION_RELATION_AND = "AND";
	private static final String CONDITION_RELATION_OR = "OR";

	private static final int NODE_STATE_ENABLE = 0;

	private static final Logger logger = LoggerFactory.getLogger(RouteFilter.class);

	public static String className = RouteFilter.class.getSimpleName();
	public static String classMethod = "run";

	CircuitBreakerService circuitBreakerService = CircuitBreakerService.getInstance();

	protected enum OperationType {
		INDEX_SERACH, INDEX_UPDATE, CLUSTER_SEARCH, CLUSTER_UPDTE
	}

	@Override
	public String name() {
		return DEFAULT_NAME;
	}

	@Override
	public void run(AbstractFilterContext filterContext, SessionContext sessionContext) throws Exception {

		PallasRequest req = sessionContext.getRequest();
		
		doESDomainCheckFromRequest(req);

		validateAuthorization(req);

		List<ServiceInfo> serviceInfoList = evaluateRouting(req);

		if(serviceInfoList == null || serviceInfoList.isEmpty()){
			throw new HttpCodeErrorPallasException("no service with " + req.getIndexName() + " by routing", HTTP_SERVICE_UNAVAILABLE, className, classMethod);
		}

		// ????????????QPS?????????????????????????????????????????????Token??????????????????????????????????????????QPS
		GaugeMonitorService.incQPS();
		sessionContext.setServiceInfoList(serviceInfoList);
		filterContext.fireFilter(sessionContext, BalanceFilter.DEFAULT_NAME);
	}

	private void doESDomainCheckFromRequest(PallasRequest req) throws ExecutionException {
		String clusterId = PallasCacheFactory.getCacheService().tryToExtractClusterId(req.getIndexName(), req.getLogicClusterId());
		req.setLogicClusterId(clusterId);
	}

	/**
	 *
	 * ?????? Request ???????????????????????????
	 * 1. ????????? uri ???method ?????????Request?????????????????????Request?????? index ??????cluster ????????????????????????
	 * 2. ??????Token ??????????????????
	 * 3. ??????????????????????????????????????????????????????
	 * 4. ?????????????????????????????????Request?????????????????????????????????
	 * 5. ?????????????????????????????????????????????????????????index ???????????????????????? 3???4
	 * 6. ????????????????????????Pallas Search ???????????????????????? "???" ?????????????????????????????????
	 * 7. ????????????
	 *
	 * @param request
	 * @return
	 * @throws Exception
	 */
	void validateAuthorization(PallasRequest request) throws Exception {

		checkScroll(request);
		if (request.isScrollContinue()) {
			return;
		}

		String indexName = request.getIndexName();
		String logicClusterId = request.getLogicClusterId();
		String clientToken = request.getClientToken();

		//??????Request ????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
		OperationType opType = checkOperation(indexName, logicClusterId, request.getUri(), request.getMethod());
		SearchAuthorization sa = PallasCacheFactory.getCacheService().getSearchAuthorization(clientToken);
		if (sa != null) {
			for (SearchAuthorization.AuthorizationItem item : sa.getAuthorizationItemList()) {
				if (!item.getName().equals(logicClusterId)) {
					continue;
				}
				if (hasClusterPrivilege(item, opType)) {
					return;
				}
				if (hasIndexPrivilege(item, indexName, opType)) {
					return;
				}
			}
		} else if (!StringUtils.isEmpty(clientToken)) {
			//???????????????????????????Client Token
			throw new HttpCodeErrorPallasException("Client-Token not found :" + clientToken, HTTP_FORBIDDEN, className, classMethod);

		} else if (!StringUtils.isEmpty(DEFAULT_AUTHORIZATION_PRIVILEGE)) {
			//?????????????????????????????????????????????  1.??????  2.ReadOnly  3.Write
			if ((SearchAuthorization.AUTHORIZATION_PRIVILEGE_READONLY.equals(DEFAULT_AUTHORIZATION_PRIVILEGE)
					&& (opType == OperationType.CLUSTER_SEARCH || opType == OperationType.INDEX_SERACH))
				|| SearchAuthorization.AUTHORIZATION_PRIVILEGE_WRITE.equals(DEFAULT_AUTHORIZATION_PRIVILEGE)) {
				return;
			}
		}

		//all
		throw new HttpCodeErrorPallasException("Operation Forbidden on index:" + indexName, HTTP_FORBIDDEN, className, classMethod);

	}

	private boolean hasIndexPrivilege(SearchAuthorization.AuthorizationItem item, String indexName, OperationType opType) {
		if (opType == OperationType.INDEX_SERACH || opType == OperationType.INDEX_UPDATE) { //NOSONAR
			for (SearchAuthorization.AuthorizationItem indexItem : item.getIndexPrivileges()) {
				if (indexItem.getName().equals(indexName)) {
					List<String> indexPrivileges = indexItem.getPrivileges().getOrDefault(SearchAuthorization.AUTHORIZATION_CAT_INDEXALL, Collections.emptyList());
					//3. ?????????????????????index???????????????????????????
					//4. ?????????index??????????????????Request??????????????????index????????????????????????
					if (indexPrivileges.contains(SearchAuthorization.AUTHORIZATION_PRIVILEGE_WRITE)
							|| (indexPrivileges.contains(SearchAuthorization.AUTHORIZATION_PRIVILEGE_READONLY) && (opType == OperationType.INDEX_SERACH))) {
						return true;
					}

				}
			}
		}
		return false;
	}

	private boolean hasClusterPrivilege(SearchAuthorization.AuthorizationItem item, OperationType opType) {

		List<String> clusterPrivileges = item.getPrivileges().getOrDefault(SearchAuthorization.AUTHORIZATION_CAT_CLUSTERALL, Collections.emptyList());
		if (clusterPrivileges.contains(SearchAuthorization.AUTHORIZATION_PRIVILEGE_WRITE)) { //NOSONAR
			//1. ??????????????????????????????????????????????????????
			return true;
		} else if (clusterPrivileges.contains(SearchAuthorization.AUTHORIZATION_PRIVILEGE_READONLY) && (opType == OperationType.CLUSTER_SEARCH || opType == OperationType.INDEX_SERACH)) { //NOSONAR
			//2. ?????????????????????????????????Request?????????????????????????????????
			return true;
		}
		return false;
	}

	/**
	 * ??????URI???method ?????????????????????????????????????????????Index????????????Cluster????????????????????????
	 * @param indexName
	 * @param logicClusterId
	 * @param uriWithQuery
	 * @param method
	 * @return
	 */
	OperationType checkOperation(String indexName, String logicClusterId, String uriWithQuery, String method) {

		String uri = uriWithQuery;
		int queryIdx = uri.indexOf('?');
		if (queryIdx > -1) {
			uri = uri.substring(0, queryIdx);
		}
		OperationType endpoint;

		if (uri.endsWith("/_search/template") || uri.contains("/_search") || uri.contains("/_render")) {
			endpoint = OperationType.INDEX_SERACH;
		} else if ((int)uri.charAt(uri.length()-1) >= 48 && (int)uri.charAt(uri.length()-1) <= 57) {//NOSONAR
			endpoint = "GET".equals(method) ? OperationType.INDEX_SERACH : OperationType.INDEX_UPDATE;
		} else if (uri.contains("/_settings") || uri.contains("/_mapping") || uri.endsWith("/" + indexName) || uri.endsWith("/" + indexName + "/")) { //NOSONAR
			endpoint = "GET".equals(method) ? OperationType.INDEX_SERACH : OperationType.INDEX_UPDATE;
		} else if ("POST".equals(method) && (uri.endsWith("/_bulk") || uri.endsWith("_update") || uri.endsWith("_update_by_query"))) {//NOSONAR
			endpoint = OperationType.INDEX_UPDATE;
		} else if ("PUT".equals(method) && uri.contains("/_create")) {//NOSONAR
			endpoint = OperationType.INDEX_UPDATE;
		} else if (uri.contains("/_delete_by_query") || "DELETE".equals(method)) { //NOSONAR
			endpoint = OperationType.INDEX_UPDATE;
		} else {
			endpoint = "GET".equals(method) ? OperationType.CLUSTER_SEARCH : OperationType.CLUSTER_UPDTE;
		}
		return endpoint;
	}



	private boolean parseCondtion(IndexRouting.Condtion condtion, PallasRequest pallasRequest){
		String clientIp = pallasRequest.getClientIp();
		String exprOp = condtion.getExprOp();
		List<String> paramValues = Arrays.asList(condtion.getParamValue().split(","));
		String paramName = condtion.getParamName();
		String paramType = condtion.getParamType();

		switch (paramType){
			case PARAM_TYPE_HEADER:
				switch (exprOp) {
					case EXPR_OP_EQUAL :
						if(StreamSupport.stream(((Iterable)() -> pallasRequest.getHeaderNames()).spliterator() //NOSONAR
								, true).anyMatch(item -> item.equals(paramName))){ // ????????????????????????header item
							return paramValues.contains(pallasRequest.getHeader(paramName));
						} else if (X_PALLAS_SEARCH_CLIENT_IP.equals(paramName)){
							return paramValues.contains(clientIp);
						}
						break;
					case EXPR_OP_MASK:
						if(StreamSupport.stream(((Iterable)() -> pallasRequest.getHeaderNames()).spliterator() //NOSONAR
								, true).anyMatch(item -> item.equals(X_PALLAS_SEARCH_CLIENT_IP))){ // ????????????????????????header item
							return paramValues.stream().anyMatch(cidr -> IPMatcher.match(pallasRequest.getHeader(X_PALLAS_SEARCH_CLIENT_IP), cidr));
						} else {
							return paramValues.stream().anyMatch(cidr -> IPMatcher.match(clientIp, cidr));
						}
					default:
						break;
				}
				break;
			default:
				break;
		}

		return false;
	}

	private List<IndexRouting.ConditionTarget> parseRouting(IndexRouting routing, PallasRequest pallasRequest){
		List<IndexRouting.RoutingCondition> conditionList = routing.getConditionList();

		for (IndexRouting.RoutingCondition condition: conditionList) {
			if(!condition.isEnable()){ //??????????????????
				continue;
			}
			boolean matched = false;

			IndexRouting.Condtion[] conditions = condition.getConditions();
			if(conditions == null || conditions.length == 0){
				matched = true;

			} else {
				switch (condition.getConditionRelation()){
					case CONDITION_RELATION_AND:
						matched = true;
						for (IndexRouting.Condtion condtion: conditions) {
							matched = parseCondtion(condtion, pallasRequest);
							if(!matched) {
								break;
							}
						}
						break;
					case CONDITION_RELATION_OR:
						for (IndexRouting.Condtion condtion: conditions) {
							matched = parseCondtion(condtion, pallasRequest);
							if(matched) {
								break;
							}
						}
						break;
					default:
						break;
				}
			}

			if(matched){
				if (!StringUtils.isEmpty(condition.getPreference())) {
					pallasRequest.setPreference(condition.getPreference());
				}
				return condition.getTargetGroups();
			}
		}

		return null;
	}

	List<ServiceInfo> parseTargetGroup(String indexName, List<String> availableNodes, List<IndexRouting.ConditionTarget> targetGroupIds, PallasRequest req) throws ExecutionException {

		List<IndexRouting.ConditionTarget> randomList = new ArrayList<>(targetGroupIds);

		while (randomList.size() > 0) {
			//??????????????????TargetGroup??????????????????TargetGroup ???????????????????????????????????????????????????????????????????????????????????????TargetGroup???????????????
			IndexRouting.ConditionTarget target = randomTargetGroupByWeight(randomList);
			if (target == null) {//??????
				return null;
			}
			randomList.remove(target);

			IndexRoutingTargetGroup targetGroup = PallasCacheFactory.getCacheService().getTargetGroupById(target.getId());
			List<ServiceInfo> targetList = getServerinfoByTargetgroup(indexName, target, targetGroup, availableNodes, req);
			if (targetList != null) {
				return targetList;
			}
		}

		return null;
	}

	private List<ServiceInfo> getServerinfoByTargetgroup(String indexName, IndexRouting.ConditionTarget target, IndexRoutingTargetGroup targetGroup, List<String> availableNodes, PallasRequest req) throws ExecutionException {
		List<IndexRoutingTargetGroup.NodeInfo> nodeInfoList = targetGroup.getNodeInfoList();
		List<IndexRoutingTargetGroup.ClusterInfo> clusterInfoList = targetGroup.getClusterInfoList();
		boolean isIndexRouting = IndexRouting.ROUTE_TYPE_INDEX.equals(targetGroup.getType());

		if(targetGroup.isNormalLevel() && nodeInfoList != null && !nodeInfoList.isEmpty()){ // ????????????
			List<ServiceInfo> targetList = nodeInfoList.stream()
					.filter(nodeInfo -> availableNodes.contains(nodeInfo.getAddress())) //??????????????????????????????
					.filter(nodeInfo -> nodeInfo.getState() == NODE_STATE_ENABLE)  //????????????
					.map(nodeInfo -> new ServiceInfo(nodeInfo.getAddress() +
							":" + (isIndexRouting ? PallasCacheFactory.getCacheService().getClusterPortByIndexAndCluster(indexName, nodeInfo.getCluster()) : PallasCacheFactory.getCacheService().getClusterPortByClusterName(nodeInfo.getCluster()))
							, indexName, req.getLogicClusterId(), targetGroup.getName()))
					.collect(toList());
			if(targetList != null && !targetList.isEmpty()){
				req.setTargetGroupId(target.getId());
				return targetList;
			}
		} else if(targetGroup.isShardLevel() && clusterInfoList != null && !clusterInfoList.isEmpty()){ // ????????????????????????
			return genShardLevelRoutingServerList(indexName, target, targetGroup, req, clusterInfoList, availableNodes);
		} else if(targetGroup.isClusterLevel0() && clusterInfoList != null && !clusterInfoList.isEmpty()){ // ????????????
			for (IndexRoutingTargetGroup.ClusterInfo clusterInfo: clusterInfoList) {
				List<String> nodeList = PallasCacheFactory.getCacheService().getAllNodeListByClusterName(clusterInfo.getName());
				if(nodeList != null && !nodeList.isEmpty()){
					req.setTargetGroupId(target.getId());
					//??????????????????targetGroup ???????????????????????????????????? ???/??? ????????????
					req.setRoutePrimaryFirst(targetGroup.isClusterPrimaryFirstLevel());
					req.setRouteReplicaFirst(targetGroup.isClusterReplicaFirstLevel());
					return nodeList.stream()
							.filter(availableNodes::contains) //??????????????????????????????
							.map(node -> new ServiceInfo(node +
									":" + PallasCacheFactory.getCacheService().getClusterPortByIndexAndCluster(indexName, clusterInfo.getCluster())
									, indexName, req.getLogicClusterId(), targetGroup.getName()))
							.collect(toList());
				}
			}
		} else if (targetGroup.isGroupLevel()) { // ????????????
			List<ShardGroup> groupList = targetGroup.getShardGroupList();
			// if group size less than 2 or the preference is not null(preference from config comes higher priority), fall back to shard routing.
			if (req.getPreference() != null || groupList.size() < 2) {
				return genShardLevelRoutingServerList(indexName, target, targetGroup, req, clusterInfoList, availableNodes);
			}
			//  filter the circuitBreaker-open groups.
			for (Iterator<ShardGroup> ite = groupList.iterator(); ite.hasNext();) {
				ShardGroup g = ite.next();
				if (circuitBreakerService.getOpenGroupsList().contains(g.getId())) {
					ite.remove();
				}
			}
			if (groupList.isEmpty()) { // no group? fall back to shard routing
				return genShardLevelRoutingServerList(indexName, target, targetGroup, req, clusterInfoList, availableNodes);
			}
			
			// random a group
			int randomIndex = InternalThreadLocalMap.get().random().nextInt(groupList.size());
			ShardGroup group = groupList.get(randomIndex);
			// circuitBreaker is on when it's shardGroup routing.
			req.setCircuitBreaker(true);
			req.setShardGroup(group);
			req.setShardGroupList(groupList);
			req.setPreference("_prefer_nodes:" + group.getPreferNodes());
			// construct the serviceInfoList
			List<String> nodeList = group.getServerList();
			List<ServiceInfo> finalNodeList = nodeList.stream().map(nodeIp -> new ServiceInfo(nodeIp,
					targetGroup.getIndexName(), req.getLogicClusterId(), targetGroup.getName())).collect(Collectors.toList());
			return finalNodeList;
		}
		return null;
	}

	public List<ServiceInfo> genShardLevelRoutingServerList(String indexName, IndexRouting.ConditionTarget target,
			IndexRoutingTargetGroup targetGroup, PallasRequest req,
			List<IndexRoutingTargetGroup.ClusterInfo> clusterInfoList, List<String> availableNodes)
			throws ExecutionException {
		for (IndexRoutingTargetGroup.ClusterInfo clusterInfo : clusterInfoList) {
			List<String> nodeList = PallasCacheFactory.getCacheService().getShardNodeListByIndexAndCluster(indexName,
					clusterInfo.getCluster());
			if (nodeList != null && !nodeList.isEmpty()) {
				req.setTargetGroupId(target.getId());
				return nodeList.stream().filter(availableNodes::contains) // ??????????????????????????????
						.map(node -> new ServiceInfo(
								node + ":"
										+ PallasCacheFactory.getCacheService()
												.getClusterPortByIndexAndCluster(indexName, clusterInfo.getCluster()),
								indexName, req.getLogicClusterId(), targetGroup.getName()))
						.collect(toList());
			}
		}
		return null;
	}

	/**
	 * ?????????TargetGroup ?????????????????????????????????????????????????????????TargetGroup????????? [{id:1, weight:70}, {id:2,weight:20}, {id:3, weight:10}]
	 * ???????????? id:1 ?????????????????????70%??? id:2 ????????????20% ????????????
	 * @param randomList
	 * @return
	 */
	IndexRouting.ConditionTarget randomTargetGroupByWeight(List<IndexRouting.ConditionTarget> randomList) {
		if (randomList.size() == 1) {//????????????targetGroup????????????????????????
			return randomList.get(0);
		}

		int weight = randomList.stream().mapToInt(IndexRouting.ConditionTarget::getWeight).sum();
		if (weight <= 0) {
			//never should go here
			logger.warn("???????????????????????????{}, group IDs:{}", weight, randomList);
			randomList.get(0);
		}

		int selected = InternalThreadLocalMap.get().random().nextInt(weight) + 1;
		for(IndexRouting.ConditionTarget t : randomList) {
			selected -= t.getWeight();
			if (selected <= 0) {
				return t;
			}
		}
		//never should go here
		logger.warn("?????????????????????{}, group IDs:{}", selected, randomList);
		return null;
	}

	private List<ServiceInfo> evaluateRouting(PallasRequest req) throws ExecutionException {
		if (req.isScrollContinue()) {//?????????POST ???????????? ????????? DELETE ???????????????scrollId ?????????
			return extractTargetGroupFromScrollId(req);
		}
		String indexName = req.getIndexName();
		String cluster = req.getLogicClusterId();
		boolean isIndexRouting = true;

		List<IndexRouting> routingList = PallasCacheFactory.getCacheService().getIndexLevelRoutingByIndexNameAndCluster(indexName, cluster);
		if (routingList == null || routingList.isEmpty()) {
			routingList = PallasCacheFactory.getCacheService().getClusterLevelRoutingByIndexNameAndCluster(cluster);
			isIndexRouting = false;
			if (routingList == null || routingList.isEmpty()) {
				throw new HttpCodeErrorPallasException("none Routing List found for index or cluster:" + indexName, HTTP_SERVICE_UNAVAILABLE, className, classMethod);
			}
		}
		req.setIsIndexSearch(isIndexRouting);
		Long routeOwnerId = routingList.get(0).getIndexId();

		List<IndexRoutingTargetGroup> targetGroupList = PallasCacheFactory.getCacheService().getTargetGroupByIndexId(routeOwnerId);
		if(targetGroupList == null || targetGroupList.isEmpty()){
			throw new HttpCodeErrorPallasException("no targetGroup found with " + indexName, HTTP_SERVICE_UNAVAILABLE, className, classMethod);
		}

		List<String> availableNodes;
		if (isIndexRouting) {
			availableNodes = PallasCacheFactory.getCacheService().getAvailableNodesByIndex(indexName);
		} else {
			availableNodes = PallasCacheFactory.getCacheService().getAvailableNodesByCluster(cluster);
		}

		if(availableNodes == null || availableNodes.isEmpty()){
			throw new HttpCodeErrorPallasException("no available nodes with " + indexName, HTTP_SERVICE_UNAVAILABLE, className, classMethod);
		}

		if (!routingList.isEmpty()){
			for(IndexRouting indexRouting : routingList){
				List<IndexRouting.ConditionTarget> targetGroupIds = parseRouting(indexRouting, req);

				if(targetGroupIds != null && !targetGroupIds.isEmpty()){
					List<ServiceInfo> serviceInfoList = parseTargetGroup(indexName, availableNodes, targetGroupIds, req);
					if(serviceInfoList != null && !serviceInfoList.isEmpty()){
						return serviceInfoList;
					}
				}
			}
		}

		return null;
	}

	/**
	 * ???????????? /_search/scroll, ?????????????????????pallas search ???scroll??????????????????targetGroup ?????? Response ??? scrollId ??????
	 * ??????????????????targetGroup ?????????????????????????????????????????????????????????????????????????????????
	 * @param req
	 * @return
	 * @throws ExecutionException
	 */
	List<ServiceInfo> extractTargetGroupFromScrollId(PallasRequest req) throws ExecutionException {
		String reqBody = req.getBodyStrForPost();
		if (StringUtils.isEmpty(reqBody)) {
			return emptyList();
		}
		int scrollIdIdx = reqBody.indexOf("\"scroll_id\"");
		if (scrollIdIdx == -1) {
			return emptyList();
		}
		int firstQuoIdx = reqBody.indexOf('\"', scrollIdIdx+12);
		int secondQuoIdx = reqBody.indexOf('\"', firstQuoIdx+1);
		if (firstQuoIdx == -1 || secondQuoIdx == -1) {
			return emptyList();
		}
		String scrollId = reqBody.substring(firstQuoIdx+1, secondQuoIdx);
		Long targetGroupId = 0L;
		try {
			targetGroupId = Long.valueOf(scrollId.substring(scrollId.indexOf('[')+1, scrollId.indexOf(']')));
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new HttpCodeErrorPallasException("cannot find [targetGroupId] in the Scroll Request", HTTP_SERVICE_UNAVAILABLE, className, classMethod);
		}

	    byte[] fixContent = reqBody.replace(scrollId, scrollId.substring(0, scrollId.indexOf('['))).getBytes();
		req.setFixScrollRequestContent(fixContent);


		IndexRoutingTargetGroup targetGroup = PallasCacheFactory.getCacheService().getTargetGroupById(targetGroupId);
		String name = targetGroup.getIndexName();
		List<String> availableNodes;
		if (IndexRouting.ROUTE_TYPE_INDEX.equals(targetGroup.getType())) {
			availableNodes = PallasCacheFactory.getCacheService().getAvailableNodesByIndex(name);
		} else {
			availableNodes = PallasCacheFactory.getCacheService().getAvailableNodesByCluster(name);
		}


		List<IndexRouting.ConditionTarget> list = Collections.singletonList(new IndexRouting.ConditionTarget(targetGroupId, 1));
		return parseTargetGroup(name, availableNodes, list, req);
	}

	private void checkScroll(PallasRequest req) {
		if (req.getUri().contains("scroll=")) {
			req.removeHeader("Accept-Encoding");
			req.setScrollFirst(true);
		}
		if (req.getUri().endsWith("_search/scroll")) {
			req.removeHeader("Accept-Encoding");
			req.setScrollContinue(true);
		}
	}

}
