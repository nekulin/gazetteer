package me.osm.gazetteer.web.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import me.osm.gazetteer.web.ESNodeHodel;
import me.osm.gazetteer.web.utils.OSMDocSinglton;
import me.osm.osmdoc.model.Feature;

import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.search.Explanation;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.common.lucene.search.function.CombineFunction;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.DisMaxQueryBuilder;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders;
import org.elasticsearch.search.sort.SortBuilders;
import org.json.JSONObject;
import org.restexpress.Request;
import org.restexpress.Response;

public class SearchAPI {

	/**
	 * Explain search results or not 
	 * (<code>true<code> for explain)
	 * default is false
	 * */
	public static final String EXPLAIN_HEADER = "explain";
	
	/**
	 * Querry string
	 * */
	public static final String Q_HEADER = "q";
	
	/**
	 * Type of feature. [adrpnt, poipnt etc]
	 * */
	public static final String TYPE_HEADER = "type";

	/**
	 * Include or not object full geometry
	 * (<code>true<code> to include)
	 * default is false
	 * */
	public static final String FULL_GEOMETRY_HEADER = "full_geometry";

	/**
	 * Search inside given BBOX
	 * west, south, east, north'
	 * */
	public static String BBOX_HEADER = "bbox";
	
	/**
	 * Look for poi of exact types
	 * */
	public static final String POI_CLASS_HEADER = "poiclass";

	/**
	 * Look for poi of exact types (from hierarchy branch)
	 * */
	public static final String POI_GROUP_HEADER = "poigroup";
	
	/**
	 * Latitude of map center
	 * */
	public static final String LAT_HEADER = "lat";

	/**
	 * Longitude of map center
	 * */
	public static final String LON_HEADER = "lon";
	
	private static volatile double queryNorm = 1;
	private static volatile boolean distanceScore = false;
	
	public JSONObject read(Request request, Response response) 
			throws IOException {

		boolean explain = "true".equals(request.getHeader(EXPLAIN_HEADER));
		String querry = StringUtils.stripToNull(request.getHeader(Q_HEADER));
		
		BoolQueryBuilder q = null;
			
		Set<String> types = getSet(request, TYPE_HEADER);
		String hname = request.getHeader("hierarchy");
		Set<String> poiClass = getSet(request, POI_CLASS_HEADER);
		addPOIGroups(request, poiClass, hname);
			
		if(querry == null && poiClass.isEmpty() && types.isEmpty()) {
			return null;
		}
		
		if(querry != null) {
			q = getSearchQuerry(querry);
		}
		else {
			q = QueryBuilders.boolQuery();
		}

		if(!types.isEmpty()) {
			q.must(QueryBuilders.termsQuery("type", types));
		}
		
		if(!poiClass.isEmpty()) {
			q.must(QueryBuilders.termsQuery("poi_class", poiClass));
		}
		
		QueryBuilder qb = q;

		if(request.getHeader(LAT_HEADER) != null && request.getHeader(LON_HEADER) != null && distanceScore) {
			qb = addDistanceScore(request, qb);
		}
		
		List<String> bbox = getList(request, BBOX_HEADER);
		if(!bbox.isEmpty() && bbox.size() == 4) {
			qb = addBBOXRestriction(qb, bbox);
		}
		
		Client client = ESNodeHodel.getClient();
		SearchRequestBuilder searchRequest = client.prepareSearch("gazetteer")
				.setQuery(qb)
				.addSort(SortBuilders.scoreSort())
				.setExplain(explain);
		
		searchRequest.setFetchSource(true);
		
		APIUtils.applyPaging(request, searchRequest);
		
		SearchResponse searchResponse = searchRequest.execute().actionGet();
		
		boolean fullGeometry = request.getHeader(FULL_GEOMETRY_HEADER) != null 
		&& "true".equals(request.getParameter(FULL_GEOMETRY_HEADER));
		
		JSONObject answer = APIUtils.encodeSearchResult(
				searchResponse,	fullGeometry, explain);
		
		answer.put("request", request.getHeader(Q_HEADER));
		
		APIUtils.resultPaging(request, answer);
		
		return answer;
		
	}

	private QueryBuilder addBBOXRestriction(QueryBuilder qb, List<String> bbox) {
		qb = QueryBuilders.filteredQuery(qb, 
				FilterBuilders.geoBoundingBoxFilter("center_point")
				.bottomLeft(Double.parseDouble(bbox.get(1)), Double.parseDouble(bbox.get(0)))
				.topRight(Double.parseDouble(bbox.get(3)), Double.parseDouble(bbox.get(2))));
		return qb;
	}

	private QueryBuilder addDistanceScore(Request request, QueryBuilder q) {
		Double lat = Double.parseDouble(request.getHeader(LAT_HEADER));
		Double lon = Double.parseDouble(request.getHeader(LON_HEADER));

		return addDistanceScore(q, lat, lon);
	}

	private static QueryBuilder addDistanceScore(QueryBuilder q, Double lat, Double lon) {
		QueryBuilder qb = QueryBuilders.functionScoreQuery(q, 
				ScoreFunctionBuilders.linearDecayFunction("center_point", 
						new GeoPoint(lat, lon), "200km")).boostMode(CombineFunction.AVG)
							.scoreMode("max");
		return qb;
	}

	private void addPOIGroups(Request request, Set<String> poiClass, String hname) {
		for(String s : getSet(request, POI_GROUP_HEADER)) {
			Collection<? extends Feature> hierarcyBranch = OSMDocSinglton.get().getReader().getHierarcyBranch(hname, s);
			if(hierarcyBranch != null) {
				for(Feature f : hierarcyBranch) {
					poiClass.add(f.getName());
				}
			}
		}
	}

	private Set<String> getSet(Request request, String header) {
		Set<String> types = new HashSet<String>();
		List<String> t = request.getHeaders(header);
		if(t != null) {
			for(String s : t) {
				types.addAll(Arrays.asList(StringUtils.split(s, ", []\"\'")));
			}
		}
		return types;
	}

	private List<String> getList(Request request, String header) {
		List<String> result = new ArrayList<String>();
		List<String> t = request.getHeaders(header);
		if(t != null) {
			for(String s : t) {
				result.addAll(Arrays.asList(StringUtils.split(s, ", []\"\'")));
			}
		}
		return result;
	}

	public static BoolQueryBuilder getSearchQuerry(String querry) {
		
		querry = StringUtils.remove(querry, ',');
		String[] terms = StringUtils.split(querry, ", ");
		
		MatchQueryBuilder q = QueryBuilders.matchQuery("search", querry);
		q.minimumShouldMatch("3<-1");
		if(terms.length == 1) {
			q.minimumShouldMatch("1");
		}
		
		return QueryBuilders.boolQuery().must(q);
		
	}
	
//	public static void updateQueryNorm () {
//		QueryBuilder q = getSearchQuerry("1");
//		Client client = ESNodeHodel.getClient();
//		SearchRequestBuilder searchRequest = client.prepareSearch("gazetteer")
//				.setQuery(addDistanceScore(q, new Double(0.0), new Double(0.0)))
//				.setSize(1)
//				.setExplain(true);
//		
//		SearchResponse answer = searchRequest.execute().actionGet();
//		SearchHit hit = answer.getHits().iterator().next();
//		Explanation explanation = hit.getExplanation();
//		queryNorm = getQueryNorm(explanation);
//	}
	
	public static double getQueryNorm() {
		return queryNorm;
	}

	private static double getQueryNorm(Explanation explanation) {
		if("queryNorm".equals(explanation.getDescription())) {
			return explanation.getValue();
		}
		else if(explanation.getDetails() != null){
			for(Explanation e : explanation.getDetails()) {
				double d = getQueryNorm(e);
				if(d != 1.0) {
					return d;
				}
			}
		}
		
		return 1.0;
	}

	private static QueryBuilder dismax(QueryBuilder... querryes) {
		DisMaxQueryBuilder dm = QueryBuilders.disMaxQuery();
		for(QueryBuilder q : querryes) {
			dm.add(q);
		}
		return dm;
	}

	private static QueryBuilder cscore(String field, String querry, float score) {
		
		return QueryBuilders.constantScoreQuery(QueryBuilders.matchQuery(field, querry)).boost(score);
	}
	
	public static void setDistanceScoring(boolean value) {
		distanceScore = value;
	}

}
