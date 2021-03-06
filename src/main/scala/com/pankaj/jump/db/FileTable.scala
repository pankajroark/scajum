package com.pankaj.jump.db

import resource._
import com.pankaj.jump.fs.FileInfo
import com.pankaj.jump.{Path, hash}
import java.io.File

class FileTable(val db: Db) extends Table {
  val name = "FILE_TABLE"

  val createString = s"create table $name(" +
    "Path varchar(1024) not null PRIMARY KEY, " +
    "ModStamp bigint not null, " +
    "ProcessStamp bigint, " +
    "Imports varchar(60000), " +
    "Id bigint not null" +
    ")"

  override val indexInfo = Map(
    "ID_INDEX" -> List("Id")
  )

  def fileExists(file: Path): Boolean = {
    val path = quote(file.toString)
    queryHasResults(s"select * from $name where Path=$path")
  }

  def idForFile(file: Path): Option[Long] =
    idForFile(file.toString)

  def idForFile(file: String): Option[Long] = {
    val path = quote(file)
    (query(s"select Id from $name where Path=$path") { rs =>
      rs.getLong(1)
    }).headOption
  }

  def fileForId(id: Long): Option[Path] = {
    (query(s"select Path from $name where Id=$id") { rs =>
      Path.fromString(rs.getString(1))
    }).headOption
  }

  def fileInfo(file: Path): Option[(FileInfo, Long)] = {
    val path = quote(file.toString)
    (query(s"select * from $name where Path=$path") { rs =>
      (FileInfo(rs.getString(1), rs.getLong(2)), rs.getLong(3))
    }).headOption
  }

  def addFileWithModStamp(fileInfo: FileInfo) = {
    val file = quote(fileInfo.path.toString)
    val fileNameHash = hash(file)
    val modStamp = fileInfo.modStamp.toString
    update(
      s"insert into $name (Path, ModStamp, ProcessStamp, Id)" +
      s"values ($file, $modStamp, 0, $fileNameHash)"
    )
  }

  def updateProcessStamp(path: Path, procTs: Long) = {
    val file = quote(path.toString)
    update(s"update $name set ProcessStamp=$procTs where Path=$file")
  }

  def updateModStamp(fileInfo: FileInfo) = {
    val file = quote(fileInfo.path.toString)
    val modStamp = fileInfo.modStamp.toString
    update(s"update $name set ModStamp=$modStamp where Path=$file")
  }

  def addOrUpdateFileWithModStamp(fileInfo: FileInfo) = {
    // check if entry already exists, if so update
    // else create a new entry
    if (fileExists(fileInfo.path)) updateModStamp(fileInfo)
    else addFileWithModStamp(fileInfo)
  }

  def addOrUpdateFileWithModStamp(f: File) = {
    val fileName = f.getPath
    val fileNameHash = hash(fileName)
    val path = quote(fileName)
    val modStamp = f.lastModified.toString
    val stmt = s"merge into $name (Path, ModStamp, Id) KEY(Path) VALUES($path, $modStamp, $fileNameHash)"
    update(stmt)
  }

  // Returns a stream of results
  // todo figure out stream close mechanism:
  // how do we make sure the underlying result set
  // is closed?
  def allFiles: Stream[(FileInfo, Long)] = {
    queryStream(s"SELECT * FROM $name") { rs =>
      (FileInfo(rs.getString(1), rs.getLong(2)), rs.getLong(3))
    }
  }

  // mainly for debugging
  def printFiles() {
    allFiles.toList.map(println _)
  }
}
