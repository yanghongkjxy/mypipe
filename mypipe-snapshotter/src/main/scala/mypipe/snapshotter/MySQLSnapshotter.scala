package mypipe.snapshotter

import com.github.mauricio.async.db.{Connection, QueryResult}
import mypipe.api.data.{ColumnMetadata, ColumnType}
import mypipe.mysql.BinaryLogFilePosition
import mypipe.mysql.Util
import mypipe.snapshotter.splitter.{BoundingValues, InputSplit, IntegerSplitter}
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}

/** Interesting parameters:
 *  - boundary-query: used for creating splits
 *  - query: import the results of this query
 *  - split-by: column used to split work units
 *  - where: where clause used during import
 *
 *  By default we will use the query:
 *   select min(<split-by>), max(<split-by>) from <table name>
 *  to find out boundaries for creating splits.
 *
 *  In some cases this query is not the most optimal
 *  so you can specify any arbitrary query returning
 *  two numeric columns using boundary-query argument.
 *
 *  By default, the split-by column is the primary key.
 *
 *  1. Determine primary key and use as split-by column, or used given split-by column
 *  2. Based on type of split-by column, determine data ranges for the table
 *  3. For each data range, use built in select query or use provided query to fetch data
 *  4. For each row, turn it into a SelectEvent and push it into the SelectConsumer's pipe.
 */
object MySQLSnapshotter {

  val log = LoggerFactory.getLogger(getClass)

  val trxIsolationLevel = "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ"
  val autoCommit = "SET autocommit=0"
  val flushTables = "FLUSH TABLES"
  val readLock = "FLUSH TABLES WITH READ LOCK"
  val showMasterStatus = "SHOW MASTER STATUS"
  val unlockTables = "UNLOCK TABLES"
  val commit = "COMMIT"

  private def getSplitByColumnFromPrimaryKey(db: String, table: String)(implicit c: Connection, ec: ExecutionContext): Future[Option[ColumnMetadata]] = {
    for (
      _ ← c.sendQuery(s"use information_schema");
      columnsMap ← Util.getTableColumns(db, table, c);
      primaryKeyColumnNamesOpt ← Util.getPrimaryKey(db, table, c)
    ) yield {
      val columns = Util.createColumns(columnsMap)
      log.info(s"Found primary key: $primaryKeyColumnNamesOpt")
      log.debug(s"Found columns: $columns")
      primaryKeyColumnNamesOpt flatMap { primaryKeyColumnNames ⇒
        val primaryKey = Util.getPrimaryKeyFromColumns(primaryKeyColumnNames, columns)
        if (primaryKey.columns.size == 1) Some(primaryKey.columns.head)
        else None
      }
    }
  }

  private def getSplitByColumn(db: String, table: String, colName: String)(implicit c: Connection, ec: ExecutionContext): Future[Option[ColumnMetadata]] = {
    for (
      _ ← c.sendQuery(s"USE information_schema");
      columnsMap ← Util.getTableColumns(db, table, c)
    ) yield {
      val columns = Util.createColumns(columnsMap)
      log.debug(s"Found columns: $columns")
      columns.find(_.name.equals(colName))
    }
  }

  private def getSelectQuery(db: String, table: String, selectQuery: Option[String]): String = {
    selectQuery match {
      case Some(q) ⇒
        log.info(s"Using user provided select query: $q")
        q
      case None ⇒
        val q = s"""SELECT * FROM $table"""
        log.info(s"Using built int select query: $q")
        q
    }
  }

  def snapshot(db: String, table: String, numSplits: Int, splitLimit: Int, splitByColumnName: Option[String], selectQuery: Option[String], whereClause: Option[String], eventHandler: Seq[SnapshotterEvent] ⇒ Boolean)(implicit c: Connection, ec: ExecutionContext) = {

    val splitbyColumnOptF = splitByColumnName match {
      case Some(colName) ⇒
        log.info(s"Using user provided split by column '${splitByColumnName.get}'.")
        getSplitByColumn(db, table, colName)
      case None ⇒
        log.info("Searching for primary key to user as split by column.")
        getSplitByColumnFromPrimaryKey(db, table)
    }

    val splitQueriesListF: Future[List[(String, String)]] = splitbyColumnOptF flatMap {
      case Some(splitByColumn) ⇒

        // get splits
        log.info(s"Trying to use column '${splitByColumn.name}' as split-by-column (isPrimaryKey=${splitByColumn.isPrimaryKey}, colType=${splitByColumn.colType})")
        val splitsF = getSplits(db, table, splitByColumn, numSplits, splitLimit)

        // TODO: handle no splits returned
        // create a series of:
        // $table -> "use $db"
        // $table -> "select * from $table ..."
        val splitQueriesF = splitsF map { splits ⇒

          val query = getSelectQuery(db, table, selectQuery)
          val additionalWhereClause = whereClause.map { case w if w.length > 0 ⇒ s"AND ($w)" } getOrElse ""

          splits.map { split ⇒
            (s"$db.$table" → s"use $db",
              s"$db.$table" → s"""$query WHERE ${split.lowClausePrefix} AND ${split.upperClausePrefix} $additionalWhereClause""")
          }.foldLeft(List.empty[(String, String)]) { (acc: List[(String, String)], v: ((String, String), (String, String))) ⇒
            acc ++ List(v._1, v._2)
          }
        }

        splitQueriesF

      case None if splitByColumnName.isDefined ⇒
        log.error(s"Could not use user provided column '$splitByColumnName' as a split-by column, aborting.")
        Future.successful(List.empty[(String, String)])
      case None ⇒
        log.error("Could not find a suitable primary key to use as a split-by column, aborting.")
        Future.successful(List.empty[(String, String)])
    }

    splitQueriesListF flatMap { splitQueriesList ⇒
      val queries = queriesWithoutTxn(splitQueriesList)
      _executeQueries(queries, eventHandler)
    }
  }

  private def _executeQueries(queries: Seq[(String, String)], eventHandler: Seq[SnapshotterEvent] ⇒ Boolean)(implicit c: Connection, ec: ExecutionContext): Future[Boolean] = {

    val queryKey = queries.head._1
    val queryVal = queries.head._2

    log.info(s"Running query: $queryVal")

    c.sendQuery(queryVal).map { queryResult ⇒

      if (!queryKey.isEmpty) {
        val events = snapshotToEvents(Seq(queryKey → queryResult))

        val successfullyHandled = if (events.nonEmpty) {
          eventHandler(events)
        } else {
          true
        }

        (queries.tail, successfullyHandled)
      } else {
        (Seq.empty, true)
      }

    } flatMap {
      case (queries, true) if queries.nonEmpty ⇒
        _executeQueries(queries, eventHandler)
      case (queries, false) if queries.nonEmpty ⇒
        log.error(s"Failed while handling events, aborting, left over queries: $queries")
        Future.successful(false)
      case (queries, true) if queries.isEmpty ⇒
        log.info("Successfully finished running queries.")
        Future.successful(true)
      case x ⇒
        log.error(s"Unknown error encountered: $x")
        Future.successful(false)
    }
  }

  protected def getBoundingValues[T](db: String, table: String, splitByCol: ColumnMetadata)(implicit c: Connection, ec: ExecutionContext): Future[BoundingValues[T]] = {
    val boundingQuery = s"""SELECT MIN(${splitByCol.name}), MAX(${splitByCol.name}) FROM $db.$table""" // TODO: append user provided WHERE clause if any

    c.sendQuery(s"use $db") flatMap { _ ⇒
      val boundingValuesF = c.sendQuery(boundingQuery)
      boundingValuesF map { boundingValues ⇒
        val r = boundingValues.rows flatMap { rows ⇒
          rows.headOption map { row ⇒
            val lowerBound = row(0)
            val upperBound = row(1)

            BoundingValues(
              if (lowerBound == null) None else Some(lowerBound.asInstanceOf[T]),
              if (upperBound == null) None else Some(upperBound.asInstanceOf[T])
            )
          }
        }

        r.getOrElse(BoundingValues(None, None))
      }
    }
  }

  def getSplits(db: String, table: String, splitByCol: ColumnMetadata, numSplits: Int, splitLimit: Int)(implicit c: Connection, ec: ExecutionContext): Future[List[InputSplit]] = {

    splitByCol.colType match {

      case ColumnType.INT24 ⇒
        log.info(s"Split by column type is ${ColumnType.INT24}")

        getBoundingValues[Int](db, table, splitByCol)
          .map { IntegerSplitter.split(splitByCol, _, numSplits, splitLimit) }

      case x ⇒
        log.error(s"Split by column of type $x is not supported, returning empty split list.")
        Future.successful(List.empty)
    }

  }

  def snapshotToEvents(snapshot: Future[Seq[(String, QueryResult)]])(implicit ec: ExecutionContext): Future[Seq[SnapshotterEvent]] = snapshot map { snapshotToEvents }

  def snapshotToEvents(results: Seq[(String, QueryResult)]): Seq[SnapshotterEvent] = {
    results
      .map { result ⇒
        val colData = result._2.rows.map(identity) map { rows ⇒
          val colCount = rows.columnNames.length
          rows.map { row ⇒
            (0 until colCount) map { i ⇒
              row(i)
            }
          }
        }

        val firstDot = result._1.indexOf('.')
        if (firstDot == result._1.lastIndexOf('.') && firstDot > 0 && result._2.rows.nonEmpty) {
          val Array(db, table) = result._1.split('.')
          Some(SelectEvent(db, table, colData.getOrElse(Seq.empty)))
        } else if (result._1.equals("showMasterStatus")) {
          colData.getOrElse(Seq.empty).headOption.map(c ⇒
            ShowMasterStatusEvent(BinaryLogFilePosition(c(0).asInstanceOf[String], c(1).asInstanceOf[Long])))
        } else {
          None
        }
      }
      // remove Nones
      .filter(_.isDefined)
      // remove the option
      .map(_.get)
  }

  private def queriesWithoutTxn(tableQueries: Seq[(String, String)]) = Seq(
    "showMasterStatus" → showMasterStatus
  ) ++ tableQueries

  private def queriesWithTxn(tableQueries: Seq[(String, String)]) = Seq(
    "" → trxIsolationLevel,
    "" → autoCommit,
    "" → flushTables,
    "" → readLock,
    "showMasterStatus" → showMasterStatus,
    "" → unlockTables
  ) ++ tableQueries ++ Seq(
      "" → commit
    )

}
