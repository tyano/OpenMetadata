package org.openmetadata.service.elasticsearch.indexresolver;

import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.index.query.QueryStringQueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NgramElasticSearchIndexResolver extends DefaultElasticSearchIndexResolver {
  private static final Logger LOGGER = LoggerFactory.getLogger(NgramElasticSearchIndexResolver.class);

  @Override
  public QueryStringQueryBuilder customizeQuery(QueryStringQueryBuilder builder) {
    return builder.type(MultiMatchQueryBuilder.Type.PHRASE);
  }

  @Override
  public IndexInfo indexInfo(IndexType type) {
    LOGGER.debug("Using NgramElasticSearchIndexResolver");
    IndexInfo indexInfo = super.indexInfo(type);
    String indexName = indexInfo.getIndexName();

    switch (type) {
      case TABLE_SEARCH_INDEX:
        return new IndexInfo(indexName, "/elasticsearch/ngram/table_index_mapping.json");
      case TOPIC_SEARCH_INDEX:
        return new IndexInfo(indexName, "/elasticsearch/ngram/topic_index_mapping.json");
      case DASHBOARD_SEARCH_INDEX:
        return new IndexInfo(indexName, "/elasticsearch/ngram/dashboard_index_mapping.json");
      case PIPELINE_SEARCH_INDEX:
        return new IndexInfo(indexName, "/elasticsearch/ngram/pipeline_index_mapping.json");
      case USER_SEARCH_INDEX:
        return new IndexInfo(indexName, "/elasticsearch/ngram/user_index_mapping.json");
      case TEAM_SEARCH_INDEX:
        return new IndexInfo(indexName, "/elasticsearch/ngram/team_index_mapping.json");
      case GLOSSARY_SEARCH_INDEX:
        return new IndexInfo(indexName, "/elasticsearch/ngram/glossary_index_mapping.json");
      case MLMODEL_SEARCH_INDEX:
        return new IndexInfo(indexName, "/elasticsearch/ngram/mlmodel_index_mapping.json");
      case TAG_SEARCH_INDEX:
        return new IndexInfo(indexName, "/elasticsearch/ngram/tag_index_mapping.json");
      case ENTITY_REPORT_DATA_INDEX:
        return new IndexInfo(indexName, "/elasticsearch/ngram/entity_report_data_index.json");
      case WEB_ANALYTIC_ENTITY_VIEW_REPORT_DATA_INDEX:
        return new IndexInfo(indexName, "/elasticsearch/ngram/web_analytic_entity_view_report_data_index.json");
      case WEB_ANALYTIC_USER_ACTIVITY_REPORT_DATA_INDEX:
        return new IndexInfo(indexName, "/elasticsearch/ngram/web_analytic_user_activity_report_data_index.json");
      default:
        throw new IllegalArgumentException("No such IndexType:" + type);
    }
  }
}