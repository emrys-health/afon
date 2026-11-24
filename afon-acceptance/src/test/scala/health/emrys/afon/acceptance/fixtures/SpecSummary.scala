package health.emrys.afon.acceptance.fixtures

import org.scalatest.events._
import org.scalatest.{Args, Reporter, Status, Suite}

/**
 * Prints to the console, when a spec has run, a line with its name, its verdict, how long it took and how many of its tests passed and failed,
 * eg {{{=== UsersSpec: PASSED in 1.1s (5 passed, 0 failed, 6 tests) ===}}}
 */
trait SpecSummary extends Suite {

  abstract override def run(testName: Option[String], args: Args): Status = {
    val collector = new SummaryCollector(args.reporter)
    val started = System.nanoTime()
    val status = super.run(testName, args.copy(reporter = collector))
    // waits for the spec to complete, as a spec may run its tests asynchronously
    val succeeded = status.succeeds()
    val elapsedMillis = (System.nanoTime() - started) / 1000000
    println(collector.summary(suiteName, succeeded, elapsedMillis))
    status
  }
}

object SpecSummary {

  /** A duration as a person reads it, eg 850ms, 12.3s or 4m 05s */
  def format(millis: Long): String =
    if (millis < 1000) s"${millis}ms"
    else if (millis < 60000) f"${millis / 1000.0}%.1fs"
    else f"${millis / 60000}m ${(millis % 60000) / 1000}%02ds"
}

/**
 * Passes the events on to the reporter it wraps, and counts the outcomes of the tests.
 */
class SummaryCollector(delegate: Reporter) extends Reporter {

  private var tests = 0
  private var passed = 0
  private var failed = 0
  private var aborted = false

  override def apply(event: Event): Unit = {
    synchronized {
      event match {
        case _: TestSucceeded => tests += 1; passed += 1
        case _: TestFailed => tests += 1; failed += 1
        case _: TestCanceled | _: TestPending | _: TestIgnored => tests += 1
        case _: SuiteAborted => aborted = true
        case _ =>
      }
    }
    delegate(event)
  }

  def summary(suite: String, succeeded: Boolean, elapsedMillis: Long): String = synchronized {
    val verdict = if (succeeded && !aborted) "PASSED" else "FAILED"
    s"=== $suite: $verdict in ${SpecSummary.format(elapsedMillis)} ($passed passed, $failed failed, $tests tests) ==="
  }
}
