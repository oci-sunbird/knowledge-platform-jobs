package org.sunbird.job.migration.helpers

import com.datastax.driver.core.Row
import com.datastax.driver.core.querybuilder.{Clause, QueryBuilder}
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import org.sunbird.job.exception.InvalidInputException
import org.sunbird.job.task.CassandraDataMigrationConfig
import org.sunbird.job.util._

trait CassandraDataMigrator {

	private[this] val logger = LoggerFactory.getLogger(classOf[CassandraDataMigrator])

	def migrateData(config: CassandraDataMigrationConfig)(implicit cassandraUtil: CassandraUtil): Unit = {

		// select primary key Column rows from table to migrate
		val primaryKeys = readPrimaryKeysFromCassandra(config)
		logger.info(s"CassandraDataMigrator:: migrateData:: After fetching primary keys. Keys Count:: " + primaryKeys.size())
		primaryKeys.forEach(col => {
			val primaryKey = config.primaryKeyColumnType.toLowerCase match {
				case "uuid" => col.getUUID(config.primaryKeyColumn)
				case _ => col.getString(config.primaryKeyColumn)
			}
			val row = readColumnDataFromCassandra(primaryKey, config)(cassandraUtil)
			if(row != null) {
				val fetchedData: String = row.getString(config.columnToMigrate)
				logger.info(s"CassandraDataMigrator:: migrateData:: Fetched ${config.columnToMigrate} in Cassandra For $primaryKey :: $fetchedData")

				val migratedData = StringUtils.replaceEach(fetchedData, config.keyValueMigrateStrings.keySet().toArray().map(_.asInstanceOf[String]), config.keyValueMigrateStrings.values().toArray().map(_.asInstanceOf[String]))

				// Pass updated data to row using primaryKey field
				updateMigratedDataToCassandra(migratedData, primaryKey, config) (cassandraUtil)
			}
		})

	}

	def readPrimaryKeysFromCassandra(config: CassandraDataMigrationConfig)(implicit cassandraUtil: CassandraUtil): java.util.List[Row] = {
		val query =  s"""select ${config.primaryKeyColumn} from ${config.cassandraKeyspace}.${config.cassandraTable} ALLOW FILTERING;"""
		cassandraUtil.find(query)
	}

	def readColumnDataFromCassandra(primaryKey: AnyRef, config: CassandraDataMigrationConfig)(implicit cassandraUtil: CassandraUtil): Row = {
		val query = config.primaryKeyColumnType.toLowerCase match {
			case "uuid" => config.columnToMigrateType.toLowerCase match {
				case "blob" => s"""select blobAsText(${config.columnToMigrate}) as ${config.columnToMigrate} from ${config.cassandraKeyspace}.${config.cassandraTable} where ${config.primaryKeyColumn}=$primaryKey ALLOW FILTERING;"""
				case _ => s"""select ${config.columnToMigrate} from ${config.cassandraKeyspace}.${config.cassandraTable} where ${config.primaryKeyColumn}=$primaryKey ALLOW FILTERING;"""
			}
			case _ => config.columnToMigrateType.toLowerCase match {
				case "blob" => s"""select blobAsText(${config.columnToMigrate}) as ${config.columnToMigrate} from ${config.cassandraKeyspace}.${config.cassandraTable} where ${config.primaryKeyColumn}='$primaryKey' ALLOW FILTERING;"""
				case _ => s"""select ${config.columnToMigrate} from ${config.cassandraKeyspace}.${config.cassandraTable} where ${config.primaryKeyColumn}='$primaryKey' ALLOW FILTERING;"""
			}
		}

		cassandraUtil.findOne(query)
	}

	def updateMigratedDataToCassandra(migratedData: String, primaryKey: AnyRef, config: CassandraDataMigrationConfig)(implicit cassandraUtil: CassandraUtil): Unit = {
		val update = QueryBuilder.update(config.cassandraKeyspace, config.cassandraTable)
		val clause: Clause = QueryBuilder.eq(config.primaryKeyColumn, primaryKey)
		update.where.and(clause)
		config.columnToMigrateType.toLowerCase match {
			case "blob" => update.`with`(QueryBuilder.set(config.columnToMigrate, QueryBuilder.fcall("textAsBlob", migratedData)))
			case _ => update.`with`(QueryBuilder.set(config.columnToMigrate, migratedData))
		}

		logger.info(s"CassandraDataMigrator:: updateMigratedDataToCassandra:: Updating ${config.columnToMigrate} in Cassandra For $primaryKey :: ${update}")
		val result = cassandraUtil.update(update)
		if (result) logger.info(s"CassandraDataMigrator:: updateMigratedDataToCassandra:: ${config.columnToMigrate} Updated Successfully For $primaryKey")
		else {
			logger.error(s"CassandraDataMigrator:: updateMigratedDataToCassandra:: ${config.columnToMigrate} Update Failed For $primaryKey")
			throw new InvalidInputException(s"${config.columnToMigrate} Update Failed For $primaryKey")
		}
	}

}