package org.openmetadata.service.elasticsearch;

import static org.openmetadata.service.resources.elasticSearch.BuildSearchIndexResource.ELASTIC_SEARCH_ENTITY_FQN_STREAM;
import static org.openmetadata.service.resources.elasticSearch.BuildSearchIndexResource.ELASTIC_SEARCH_EXTENSION;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.CreateIndexResponse;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.client.indices.PutMappingRequest;
import org.elasticsearch.common.xcontent.XContentType;
import org.openmetadata.schema.settings.EventPublisherJob;
import org.openmetadata.schema.settings.FailureDetails;
import org.openmetadata.schema.type.TagLabel;
import org.openmetadata.service.Entity;
import org.openmetadata.service.elasticsearch.ElasticSearchIndexResolver.IndexInfo;
import org.openmetadata.service.elasticsearch.ElasticSearchIndexResolver.IndexType;
import org.openmetadata.service.jdbi3.CollectionDAO;
import org.openmetadata.service.util.JsonUtils;

@Slf4j
public class ElasticSearchIndexDefinition {
  private final CollectionDAO dao;
  final EnumMap<IndexType, ElasticSearchIndexStatus> elasticSearchIndexes = new EnumMap<>(IndexType.class);
  private final RestHighLevelClient client;
  private final ElasticSearchIndexResolver indexResolver;

  public ElasticSearchIndexDefinition(
      RestHighLevelClient client, CollectionDAO dao, ElasticSearchIndexResolver resolver) {
    this.dao = dao;
    this.client = client;
    this.indexResolver = resolver;
    for (IndexType elasticSearchIndexType : IndexType.values()) {
      elasticSearchIndexes.put(elasticSearchIndexType, ElasticSearchIndexStatus.NOT_CREATED);
    }
  }

  public enum ElasticSearchIndexStatus {
    CREATED,
    NOT_CREATED,
    FAILED
  }

  public void createIndexes() {
    for (IndexType elasticSearchIndexType : IndexType.values()) {
      createIndex(elasticSearchIndexType);
    }
  }

  public void updateIndexes() {
    for (IndexType elasticSearchIndexType : IndexType.values()) {
      updateIndex(elasticSearchIndexType);
    }
  }

  public void dropIndexes() {
    for (IndexType elasticSearchIndexType : IndexType.values()) {
      deleteIndex(elasticSearchIndexType);
    }
  }

  public boolean checkIndexExistsOrCreate(IndexType indexType) {
    boolean exists = elasticSearchIndexes.get(indexType) == ElasticSearchIndexStatus.CREATED;
    if (!exists) {
      exists = createIndex(indexType);
    }
    return exists;
  }

  public boolean createIndex(IndexType indexType) {
    IndexInfo indexInfo = this.indexResolver.indexInfo(indexType);
    String indexName = indexInfo.getIndexName();
    try {
      GetIndexRequest gRequest = new GetIndexRequest(indexName);
      gRequest.local(false);
      boolean exists = client.indices().exists(gRequest, RequestOptions.DEFAULT);
      if (!exists) {
        String elasticSearchIndexMapping = getIndexMapping(indexType);
        CreateIndexRequest request = new CreateIndexRequest(indexName);
        request.source(elasticSearchIndexMapping, XContentType.JSON);
        CreateIndexResponse createIndexResponse = client.indices().create(request, RequestOptions.DEFAULT);
        LOG.info("{} Created {}", indexName, createIndexResponse.isAcknowledged());
      }
      setIndexStatus(indexType, ElasticSearchIndexStatus.CREATED);
    } catch (Exception e) {
      setIndexStatus(indexType, ElasticSearchIndexStatus.FAILED);
      updateElasticSearchFailureStatus(
          getContext("Creating Index", indexName),
          String.format("Reason: [%s] , Trace : [%s]", e.getMessage(), ExceptionUtils.getStackTrace(e)));
      LOG.error("Failed to create Elastic Search indexes due to", e);
      return false;
    }
    return true;
  }

  private String getContext(String type, String info) {
    return String.format("Failed While : %s \n Additional Info:  %s ", type, info);
  }

  private void updateIndex(IndexType indexType) {
    IndexInfo indexInfo = indexResolver.indexInfo(indexType);
    String indexName = indexInfo.getIndexName();
    try {
      GetIndexRequest gRequest = new GetIndexRequest(indexName);
      gRequest.local(false);
      boolean exists = client.indices().exists(gRequest, RequestOptions.DEFAULT);
      String elasticSearchIndexMapping = getIndexMapping(indexType);
      if (exists) {
        PutMappingRequest request = new PutMappingRequest(indexName);
        request.source(elasticSearchIndexMapping, XContentType.JSON);
        AcknowledgedResponse putMappingResponse = client.indices().putMapping(request, RequestOptions.DEFAULT);
        LOG.info("{} Updated {}", indexName, putMappingResponse.isAcknowledged());
      } else {
        CreateIndexRequest request = new CreateIndexRequest(indexName);
        request.source(elasticSearchIndexMapping, XContentType.JSON);
        CreateIndexResponse createIndexResponse = client.indices().create(request, RequestOptions.DEFAULT);
        LOG.info("{} Created {}", indexName, createIndexResponse.isAcknowledged());
      }
      setIndexStatus(indexType, ElasticSearchIndexStatus.CREATED);
    } catch (Exception e) {
      setIndexStatus(indexType, ElasticSearchIndexStatus.FAILED);
      updateElasticSearchFailureStatus(
          getContext("Updating Index", indexName),
          String.format("Reason: [%s] , Trace : [%s]", e.getMessage(), ExceptionUtils.getStackTrace(e)));
      LOG.error("Failed to update Elastic Search indexes due to", e);
    }
  }

  public void deleteIndex(IndexType indexType) {
    IndexInfo indexInfo = indexResolver.indexInfo(indexType);
    String indexName = indexInfo.getIndexName();
    try {
      GetIndexRequest gRequest = new GetIndexRequest(indexName);
      gRequest.local(false);
      boolean exists = client.indices().exists(gRequest, RequestOptions.DEFAULT);
      if (exists) {
        DeleteIndexRequest request = new DeleteIndexRequest(indexName);
        AcknowledgedResponse deleteIndexResponse = client.indices().delete(request, RequestOptions.DEFAULT);
        LOG.info("{} Deleted {}", indexName, deleteIndexResponse.isAcknowledged());
      }
    } catch (IOException e) {
      updateElasticSearchFailureStatus(
          getContext("Deleting Index", indexName),
          String.format("Reason: [%s] , Trace : [%s]", e.getMessage(), ExceptionUtils.getStackTrace(e)));
      LOG.error("Failed to delete Elastic Search indexes due to", e);
    }
  }

  private void setIndexStatus(IndexType indexType, ElasticSearchIndexStatus elasticSearchIndexStatus) {
    elasticSearchIndexes.put(indexType, elasticSearchIndexStatus);
  }

  public String getIndexMapping(IndexType elasticSearchIndexType) throws IOException {
    IndexInfo indexInfo = this.indexResolver.indexInfo(elasticSearchIndexType);
    InputStream in = ElasticSearchIndexDefinition.class.getResourceAsStream(indexInfo.getMappingFilePath());
    return new String(in.readAllBytes());
  }

  @Value
  @AllArgsConstructor(staticName = "of")
  public static class IndexTypeInfo {
    private IndexType indexType;
    private IndexInfo indexInfo;
  }

  public IndexTypeInfo getIndexMappingByEntityType(String type) {
    if (type.equalsIgnoreCase(Entity.TABLE)) {
      return IndexTypeInfo.of(IndexType.TABLE_SEARCH_INDEX, indexResolver.indexInfo(IndexType.TABLE_SEARCH_INDEX));
    } else if (type.equalsIgnoreCase(Entity.DASHBOARD)) {
      return IndexTypeInfo.of(
          IndexType.DASHBOARD_SEARCH_INDEX, indexResolver.indexInfo(IndexType.DASHBOARD_SEARCH_INDEX));
    } else if (type.equalsIgnoreCase(Entity.PIPELINE)) {
      return IndexTypeInfo.of(
          IndexType.PIPELINE_SEARCH_INDEX, indexResolver.indexInfo(IndexType.PIPELINE_SEARCH_INDEX));
    } else if (type.equalsIgnoreCase(Entity.TOPIC)) {
      return IndexTypeInfo.of(IndexType.TOPIC_SEARCH_INDEX, indexResolver.indexInfo(IndexType.TOPIC_SEARCH_INDEX));
    } else if (type.equalsIgnoreCase(Entity.USER)) {
      return IndexTypeInfo.of(IndexType.USER_SEARCH_INDEX, indexResolver.indexInfo(IndexType.USER_SEARCH_INDEX));
    } else if (type.equalsIgnoreCase(Entity.TEAM)) {
      return IndexTypeInfo.of(IndexType.TEAM_SEARCH_INDEX, indexResolver.indexInfo(IndexType.TEAM_SEARCH_INDEX));
    } else if (type.equalsIgnoreCase(Entity.GLOSSARY)) {
      return IndexTypeInfo.of(
          IndexType.GLOSSARY_SEARCH_INDEX, indexResolver.indexInfo(IndexType.GLOSSARY_SEARCH_INDEX));
    } else if (type.equalsIgnoreCase(Entity.MLMODEL)) {
      return IndexTypeInfo.of(IndexType.MLMODEL_SEARCH_INDEX, indexResolver.indexInfo(IndexType.MLMODEL_SEARCH_INDEX));
    } else if (type.equalsIgnoreCase(Entity.GLOSSARY_TERM)) {
      return IndexTypeInfo.of(
          IndexType.GLOSSARY_SEARCH_INDEX, indexResolver.indexInfo(IndexType.GLOSSARY_SEARCH_INDEX));
    } else if (type.equalsIgnoreCase(Entity.TAG)) {
      return IndexTypeInfo.of(IndexType.TAG_SEARCH_INDEX, indexResolver.indexInfo(IndexType.TAG_SEARCH_INDEX));
    }
    throw new RuntimeException("Failed to find index doc for type " + type);
  }

  private void updateElasticSearchFailureStatus(String failedFor, String failureMessage) {
    try {
      long updateTime = Date.from(LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant()).getTime();
      String recordString =
          dao.entityExtensionTimeSeriesDao().getExtension(ELASTIC_SEARCH_ENTITY_FQN_STREAM, ELASTIC_SEARCH_EXTENSION);
      EventPublisherJob lastRecord = JsonUtils.readValue(recordString, EventPublisherJob.class);
      long originalLastUpdate = lastRecord.getTimestamp();
      lastRecord.setStatus(EventPublisherJob.Status.ACTIVEWITHERROR);
      lastRecord.setTimestamp(updateTime);
      lastRecord.setFailureDetails(
          new FailureDetails()
              .withContext(failedFor)
              .withLastFailedAt(updateTime)
              .withLastFailedReason(failureMessage));

      dao.entityExtensionTimeSeriesDao()
          .update(
              ELASTIC_SEARCH_ENTITY_FQN_STREAM,
              ELASTIC_SEARCH_EXTENSION,
              JsonUtils.pojoToJson(lastRecord),
              originalLastUpdate);
    } catch (Exception e) {
      LOG.error("Failed to Update Elastic Search Job Info");
    }
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@Jacksonized
@Getter
@Builder
class ElasticSearchSuggest {
  String input;
  Integer weight;
}

@Getter
@Builder
class FlattenColumn {
  String name;
  String description;
  List<TagLabel> tags;
}

class ParseTags {
  TagLabel tierTag;
  final List<TagLabel> tags;

  ParseTags(List<TagLabel> tags) {
    if (!tags.isEmpty()) {
      List<TagLabel> tagsList = new ArrayList<>(tags);
      for (TagLabel tag : tagsList) {
        if (tag.getTagFQN().toLowerCase().matches("(.*)tier(.*)")) {
          tierTag = tag;
          break;
        }
      }
      if (tierTag != null) {
        tagsList.remove(tierTag);
      }
      this.tags = tagsList;
    } else {
      this.tags = tags;
    }
  }
}
