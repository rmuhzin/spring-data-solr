/*
 * Copyright 2012 - 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.solr.core;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.FacetField.Count;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Page;
import org.springframework.data.mapping.model.MappingException;
import org.springframework.data.repository.util.ClassUtils;
import org.springframework.data.solr.core.query.FacetQuery;
import org.springframework.data.solr.core.query.Field;
import org.springframework.data.solr.core.query.SimpleField;
import org.springframework.data.solr.core.query.result.FacetFieldEntry;
import org.springframework.data.solr.core.query.result.FacetQueryEntry;
import org.springframework.data.solr.core.query.result.HighlightEntry;
import org.springframework.data.solr.core.query.result.SimpleFacetFieldEntry;
import org.springframework.data.solr.core.query.result.SimpleFacetQueryEntry;
import org.springframework.data.solr.core.query.result.SolrResultPage;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Use Result Helper to extract various parameters from the QueryResponse and convert it into a proper Format taking
 * care of non existent and null elements with the response.
 * 
 * @author Christoph Strobl
 */
final class ResultHelper {

	private ResultHelper() {
	}

	static Map<Field, Page<FacetFieldEntry>> convertFacetQueryResponseToFacetPageMap(FacetQuery query,
			QueryResponse response) {
		Assert.notNull(query, "Cannot convert response for 'null', query");

		if (!hasFacets(query, response)) {
			return Collections.emptyMap();
		}
		Map<Field, Page<FacetFieldEntry>> facetResult = new HashMap<Field, Page<FacetFieldEntry>>();

		if (CollectionUtils.isNotEmpty(response.getFacetFields())) {
			int initalPageSize = query.getFacetOptions().getPageable().getPageSize();
			for (FacetField facetField : response.getFacetFields()) {
				if (facetField != null && StringUtils.hasText(facetField.getName())) {
					Field field = new SimpleField(facetField.getName());
					if (CollectionUtils.isNotEmpty(facetField.getValues())) {
						List<FacetFieldEntry> pageEntries = new ArrayList<FacetFieldEntry>(initalPageSize);
						for (Count count : facetField.getValues()) {
							if (count != null) {
								pageEntries.add(new SimpleFacetFieldEntry(field, count.getName(), count.getCount()));
							}
						}
						facetResult.put(field, new SolrResultPage<FacetFieldEntry>(pageEntries, query.getFacetOptions()
								.getPageable(), facetField.getValueCount()));
					} else {
						facetResult.put(field, new SolrResultPage<FacetFieldEntry>(Collections.<FacetFieldEntry> emptyList(), query
								.getFacetOptions().getPageable(), 0));
					}
				}
			}
		}
		return facetResult;
	}

	static List<FacetQueryEntry> convertFacetQueryResponseToFacetQueryResult(FacetQuery query, QueryResponse response) {
		Assert.notNull(query, "Cannot convert response for 'null', query");

		if (!hasFacets(query, response)) {
			return Collections.emptyList();
		}

		List<FacetQueryEntry> facetResult = new ArrayList<FacetQueryEntry>();

		if (MapUtils.isNotEmpty(response.getFacetQuery())) {
			for (Entry<String, Integer> entry : response.getFacetQuery().entrySet()) {
				facetResult.add(new SimpleFacetQueryEntry(entry.getKey(), entry.getValue()));
			}
		}
		return facetResult;
	}

	static <T> List<HighlightEntry<T>> convertAndAddHighlightQueryResponseToResultPage(QueryResponse response,
			SolrResultPage<T> page) {
		if (response == null || MapUtils.isEmpty(response.getHighlighting()) || page == null) {
			return Collections.emptyList();
		}

		List<HighlightEntry<T>> mappedHighlights = new ArrayList<HighlightEntry<T>>(page.getSize());
		Map<String, Map<String, List<String>>> highlighting = response.getHighlighting();

		for (T item : page) {
			HighlightEntry<T> highlightEntry = processHighlightingForPageEntry(highlighting, item);
			mappedHighlights.add(highlightEntry);
		}
		page.setHighlighted(mappedHighlights);
		return mappedHighlights;
	}

	private static <T> HighlightEntry<T> processHighlightingForPageEntry(
			Map<String, Map<String, List<String>>> highlighting, T pageEntry) {
		HighlightEntry<T> highlightEntry = new HighlightEntry<T>(pageEntry);
		Object itemId = getMappedId(pageEntry);

		Map<String, List<String>> highlights = highlighting.get(itemId.toString());
		if (MapUtils.isNotEmpty(highlights)) {
			for (Map.Entry<String, List<String>> entry : highlights.entrySet()) {
				highlightEntry.addSnipplets(entry.getKey(), entry.getValue());
			}
		}
		return highlightEntry;
	}

	private static Object getMappedId(Object o) {
		if (ClassUtils.hasProperty(o.getClass(), "id")) {
			try {
				return FieldUtils.readDeclaredField(o, "id", true);
			} catch (IllegalAccessException e) {
				throw new MappingException("Id property could not be accessed!", e);
			}
		}

		for (java.lang.reflect.Field field : o.getClass().getDeclaredFields()) {
			Annotation annotation = AnnotationUtils.getAnnotation(field, Id.class);
			if (annotation != null) {
				try {
					return FieldUtils.readField(field, o, true);
				} catch (IllegalArgumentException e) {
					throw new MappingException("Id property could not be accessed!", e);
				} catch (IllegalAccessException e) {
					throw new MappingException("Id property could not be accessed!", e);
				}
			}
		}
		throw new MappingException("Id property could not be found!");
	}

	private static boolean hasFacets(FacetQuery query, QueryResponse response) {
		return query.hasFacetOptions() && response != null;
	}

}
