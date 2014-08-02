import com.twitter.util.{Await, Future}
import com.twitter.util.ScheduledThreadPoolTimer
import com.twitter.conversions.time._
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import com.pankaj.jump.{AltJumpService, JumpDecider, JumpHandler, Path}
import com.pankaj.jump.parser.{Parser, ParserFactory, ParseWorker}
import com.pankaj.jump.fs.{DirtFinder, DiskCrawler, RootsTracker}
import com.pankaj.jump.db.{Db, FileTable, RootsTable, SymbolTable}
import com.pankaj.jump.util.{ThreadNonBlockingActor, ThreadBlockingActor}

object Jumper {
  def main(args:Array[String]): Unit = {
    val db = new Db
    val fileTable = new FileTable(db)
    val rootsTable = new RootsTable(db)
    val symbolTable = new SymbolTable(db, fileTable)
    fileTable.setUp()
    rootsTable.setUp()
    symbolTable.setUp()
    val parserFactory = new ParserFactory
    val rootsTracker = new RootsTracker(rootsTable)
    val parseWorkerActor = new ThreadBlockingActor(new ParseWorker(fileTable, symbolTable, parserFactory), 10)
    parseWorkerActor.start()
    val dirtFinderActor = new ThreadNonBlockingActor(new DirtFinder(fileTable, parseWorkerActor))
    dirtFinderActor.start()
    dirtFinderActor.send(())
    val diskCrawlerActor = new ThreadNonBlockingActor(
      new DiskCrawler(rootsTracker, fileTable, dirtFinderActor)
    )
    diskCrawlerActor.start()

    val timer = new ScheduledThreadPoolTimer()
    val crawl_period_env = System.getenv("DISK_CRAWL_PERIOD")
    val crawl_period =
      if (crawl_period_env != null) crawl_period_env.toInt
      else 60
    timer.schedule(crawl_period.minutes) {
      try {
        diskCrawlerActor.send(())
        //fileTable.printFiles()
        //rootsTable.printRoots()
        dirtFinderActor.send(())
        //symbolTable.printAll()
      } catch {
        case e: Throwable =>
          println("error")
          e.printStackTrace
      }
    }

    val jumpDecider = new JumpDecider(parserFactory, symbolTable, fileTable)
    val jumpHandler = new JumpHandler(jumpDecider, symbolTable)
    val port_env = System.getenv("PORT")
    val port = if (port_env != null) port_env.toInt else 8081

    val altJumpService = new AltJumpService(
      rootsTracker,
      jumpHandler,
      parseWorkerActor,
      diskCrawlerActor,
      8081
    )
    altJumpService.start()
    diskCrawlerActor.send(())
  }
}
