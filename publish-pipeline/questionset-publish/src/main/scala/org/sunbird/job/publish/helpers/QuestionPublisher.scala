package org.sunbird.job.publish.helpers

import java.util

import com.datastax.driver.core.Row
import com.datastax.driver.core.querybuilder.{QueryBuilder, Select, Update}
import org.apache.commons.lang3
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import org.sunbird.job.util.{CassandraUtil, Neo4JUtil, ScalaJsonUtil}
import org.sunbird.publish.core.{ExtDataConfig, ObjectData}
import org.sunbird.publish.helpers.{ObjectEnrichment, ObjectReader, ObjectUpdater, ObjectValidator}

import scala.collection.mutable.ListBuffer
import scala.collection.JavaConverters._

trait QuestionPublisher extends ObjectReader with ObjectValidator with ObjectEnrichment with ObjectUpdater {

	private[this] val logger = LoggerFactory.getLogger(classOf[QuestionPublisher])
	val extProps = List("body", "editorState", "answer", "solutions", "instructions", "hints", "media", "responseDeclaration", "interactions")

	override def getHierarchy(identifier: String, pkgVersion: Double, readerConfig: ExtDataConfig)(implicit cassandraUtil: CassandraUtil): Option[Map[String, AnyRef]] = None

	override def enrichObjectMetadata(obj: ObjectData)(implicit neo4JUtil: Neo4JUtil, cassandraUtil: CassandraUtil, readerConfig: ExtDataConfig): Option[ObjectData] = {
		val pkgVersion = obj.metadata.getOrElse("pkgVersion", 0.0.asInstanceOf[Number]).asInstanceOf[Number].intValue() + 1
		val updatedMeta = obj.metadata ++ Map("pkgVersion" -> pkgVersion.asInstanceOf[AnyRef])
		Some(new ObjectData(obj.identifier, updatedMeta, obj.extData, obj.hierarchy))
	}

	def validateQuestion(obj: ObjectData, identifier: String): List[String] = {
		logger.info("Validating Question External Data For : " + obj.identifier)
		//TODO: Remove Below logger statement
		logger.info("Question External Data : " + obj.extData.get)
		val messages = ListBuffer[String]()
		if (obj.extData.getOrElse(Map()).getOrElse("body", "").asInstanceOf[String].isEmpty) messages += s"""There is no body available for : $identifier"""
		if (obj.extData.getOrElse(Map()).getOrElse("editorState", "").asInstanceOf[String].isEmpty) messages += s"""There is no editorState available for : $identifier"""
		val interactionTypes = obj.metadata.getOrElse("interactionTypes", new util.ArrayList[String]()).asInstanceOf[util.List[String]].asScala.toList
		interactionTypes.nonEmpty match {
			case true => {
				if (obj.extData.get.getOrElse("responseDeclaration", "").asInstanceOf[String].isEmpty) messages += s"""There is no responseDeclaration available for : $identifier"""
				if (obj.extData.get.getOrElse("interactions", "").asInstanceOf[String].isEmpty) messages += s"""There is no interactions available for : $identifier"""
			}
			case false => if (obj.extData.getOrElse(Map()).getOrElse("answer", "").asInstanceOf[String].isEmpty) messages += s"""There is no answer available for : $identifier"""
		}
		messages.toList
	}

	override def getExtData(identifier: String,pkgVersion: Double, readerConfig: ExtDataConfig)(implicit cassandraUtil: CassandraUtil): Option[Map[String, AnyRef]] = {
		val row: Row = Option(getQuestionData(getEditableObjId(identifier, pkgVersion), readerConfig)).getOrElse(getQuestionData(identifier, readerConfig))
		//TODO: covert below code in scala. entire props should be in scala.
		if (null != row) Option(extProps.map(prop => prop -> row.getString(prop.toLowerCase())).toMap.filter(p => StringUtils.isNotBlank(p._2.asInstanceOf[String]))) else Option(Map[String, AnyRef]())
	}

	def getQuestionData(identifier: String, readerConfig: ExtDataConfig)(implicit cassandraUtil: CassandraUtil): Row = {
		logger.info("QuestionPublisher ::: getQuestionData ::: Reading Question External Data For : "+identifier)
		val select = QueryBuilder.select()
		extProps.foreach(prop => if (lang3.StringUtils.equals("body", prop) | lang3.StringUtils.equals("answer", prop)) select.fcall("blobAsText", QueryBuilder.column(prop.toLowerCase())).as(prop.toLowerCase()) else select.column(prop.toLowerCase()).as(prop.toLowerCase()))
		val selectWhere: Select.Where = select.from(readerConfig.keyspace, readerConfig.table).where().and(QueryBuilder.eq("identifier", identifier))
		logger.info("Cassandra Fetch Query :: "+ selectWhere.toString)
		cassandraUtil.findOne(selectWhere.toString)
	}

	def dummyFunc = (obj: ObjectData, readerConfig: ExtDataConfig) => {}

	override def getExtDatas(identifiers: List[String], readerConfig: ExtDataConfig)(implicit cassandraUtil: CassandraUtil): Option[Map[String, AnyRef]] = {
		None
	}

	override def getHierarchies(identifiers: List[String], readerConfig: ExtDataConfig)(implicit cassandraUtil: CassandraUtil): Option[Map[String, AnyRef]] = {
		None
	}

	override def saveExternalData(obj: ObjectData, readerConfig: ExtDataConfig)(implicit cassandraUtil: CassandraUtil) = {
		val extData = obj.extData.getOrElse(Map())
		val identifier = obj.identifier.replace(".img", "")
		val query = QueryBuilder.update(readerConfig.keyspace, readerConfig.table)
			.where(QueryBuilder.eq("identifier", identifier))
			.`with`(QueryBuilder.set("body",  QueryBuilder.fcall("textAsBlob", extData.getOrElse("body", null))))
			.and(QueryBuilder.set("answer", QueryBuilder.fcall("textAsBlob", extData.getOrElse("answer", null))))
			.and(QueryBuilder.set("editorstate", extData.getOrElse("editorState", null)))
			.and(QueryBuilder.set("solutions", extData.getOrElse("solutions", null)))
			.and(QueryBuilder.set("instructions", extData.getOrElse("instructions", null)))
			.and(QueryBuilder.set("media", extData.getOrElse("media", null)))
			.and(QueryBuilder.set("hints", extData.getOrElse("hints", null)))
			.and(QueryBuilder.set("responsedeclaration", extData.getOrElse("responseDeclaration", null)))
			.and(QueryBuilder.set("interactions", extData.getOrElse("interactions", null)))

		logger.info(s"Updating Question in Cassandra For ${identifier} : ${query.toString}")
		val result = cassandraUtil.upsert(query.toString)
		if (result) {
			logger.info(s"Question Updated Successfully For ${identifier}")
		} else {
			val msg = s"Question Update Failed For ${identifier}"
			logger.error(msg)
			throw new Exception(msg)
		}
	}

	override def deleteExternalData(obj: ObjectData, readerConfig: ExtDataConfig)(implicit cassandraUtil: CassandraUtil): Unit = {
		val query = QueryBuilder.delete().from(readerConfig.keyspace, readerConfig.table)
			.where(QueryBuilder.eq("identifier", obj.dbId))
			.ifExists
		cassandraUtil.session.execute(query.toString)
	}

}