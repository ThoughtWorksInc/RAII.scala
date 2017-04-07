package com.thoughtworks.raii

import com.thoughtworks.raii.ResourceFactoryT.managed
import com.thoughtworks.raii.ResourceFactoryTSpec.Exceptions.{Boom, CanNotOpenResourceTwice}
import com.thoughtworks.raii.ResourceFactoryTSpec.FakeResource
import com.thoughtworks.raii.Shared.SharedOps
import org.scalatest.{Assertion, AsyncFreeSpec, Inside, Matchers}

import scala.collection.mutable
import scala.concurrent.Promise
import scalaz.concurrent.{Future, Task}
import scalaz.syntax.all._
import scalaz.{-\/, EitherT, \/, _}

/**
  * Created by 张志豪 on 2017/4/6.
  */
class SharedSpec extends AsyncFreeSpec with Matchers with Inside {

  "when working with scalaz's Future, it must asynchronously open and close" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val mr0 = managed[Future, FakeResource](new FakeResource(allOpenedResources, "r0"))
    allOpenedResources.keys shouldNot contain("r0")
    val asynchronousResource: Future[Unit] = mr0.using { r0 =>
      Future.delay {
        events += "using r0"
        allOpenedResources("r0") should be(r0)
      }
    }

    val p = Promise[Assertion]
    asynchronousResource.unsafePerformAsync { _ =>
      p.success {
        allOpenedResources.keys shouldNot contain("r0")
        events should be(Seq("using r0"))
      }
    }
    p.future
  }

  "when working with scalaz's EitherT" - {

    "must asynchronously open and close when an exception occurs" ignore {
      val events = mutable.Buffer.empty[String]
      val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
      val mr0 = managed[Future, FakeResource](new FakeResource(allOpenedResources, "r0"))
      allOpenedResources.keys shouldNot contain("r0")
      val asynchronousResource: Task[Unit] = new Task(mr0.using { r0 =>
        Future.delay {
          events += "using r0"
          allOpenedResources("r0") should be(r0)
          -\/(new Boom: Throwable)
        }
      })

      val p = Promise[Assertion]
      asynchronousResource.unsafePerformAsync { either =>
        p.success {
          inside(either) {
            case -\/(e) =>
              e should be(a[Boom])
          }
          allOpenedResources.keys shouldNot contain("r0")
          events should be(Seq("using r0"))
        }
      }
      p.future
    }
  }
  "when working with scalaz's Task" - {
    "must asynchronously open and close" in {
      val events = mutable.Buffer.empty[String]
      val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
      val mr0 = managed[Task, FakeResource](new FakeResource(allOpenedResources, "r0"))
      allOpenedResources.keys shouldNot contain("r0")
      val asynchronousResource: Task[Unit] = mr0.using { r0 =>
        Task.delay {
          events += "using r0"
          allOpenedResources("r0") should be(r0)
        }
      }

      val p = Promise[Assertion]
      asynchronousResource.unsafePerformAsync { _ =>
        p.success {
          allOpenedResources.keys shouldNot contain("r0")
          events should be(Seq("using r0"))
        }
      }
      p.future
    }

    "must asynchronously open and close when an exception occurs" ignore {
      import scalaz.concurrent.Task._
      val events = mutable.Buffer.empty[String]
      val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
      val mr0 = managed[Task, FakeResource](new FakeResource(allOpenedResources, "r0"))
      allOpenedResources.keys shouldNot contain("r0")
      val asynchronousResource: Task[Unit] = mr0.using { r0 =>
        events += "using r0"
        allOpenedResources("r0") should be(r0)
        (new Boom: Throwable).raiseError
      }

      val p = Promise[Assertion]
      asynchronousResource.unsafePerformAsync { _ =>
        p.success {
          allOpenedResources.keys shouldNot contain("r0")
          events should be(Seq("using r0"))
        }
      }
      p.future
    }

  }

  "reference count test without shared -- async" ignore {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val mr: ResourceFactoryT[Future, FakeResource] =
      managed[Future, FakeResource](new FakeResource(allOpenedResources, "0"))
    allOpenedResources.keys shouldNot contain("0")

    recoverToSucceededIf[CanNotOpenResourceTwice] {
      val usingResource = mr.flatMap { r1 =>
        mr.map { r2 =>
          allOpenedResources.keys should contain("0")
          events += "using 0"
        }
      }

      val asynchronousResource: Future[Unit] = usingResource.using { _ =>
        Future.now(())
      }

      val p = Promise[Assertion]

      asynchronousResource.unsafePerformAsync { _ =>
        p.success {
          allOpenedResources.keys shouldNot contain("0")
          events should be(Seq("using 0"))
        }
      }
      p.future
    }

  }

  "reference count test with shared -- async" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val mr: ResourceFactoryT[Future, FakeResource] =
      managed[Future, FakeResource](new FakeResource(allOpenedResources, "0")).shared
    allOpenedResources.keys shouldNot contain("0")

    val usingResource: ResourceFactoryT[Future, mutable.Buffer[String]] = mr.flatMap { r1 =>
      mr.map { r2 =>
        allOpenedResources.keys should contain("0")
        events += "using 0"
      }
    }

    val asynchronousResource: Future[Unit] = usingResource.using { _ =>
      Future.now(())
    }

    val p = Promise[Assertion]

    asynchronousResource.unsafePerformAsync { _ =>
      p.success {
        allOpenedResources.keys shouldNot contain("0")
        events should be(Seq("using 0"))
      }
    }
    p.future
  }

  "reference count test with shared -- async -- eitherT" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]

    val sharedResource: ResourceFactoryT[Future, FakeResource] =
      managed[Future, FakeResource](new FakeResource(allOpenedResources, "0")).shared

    val mappedResource: ResourceFactoryT[Future, Throwable \/ FakeResource] = sharedResource.map(\/.right)

    val mr = new EitherT[ResourceFactoryT[Future, ?], Throwable, FakeResource](mappedResource)

    val usingResource = mr.map { r1: FakeResource =>
      events += "using 0"
      allOpenedResources("0") should be(r1)
    }

    val future: Future[Throwable \/ Assertion] = usingResource.run.run

    val p = Promise[Assertion]

    future.unsafePerformAsync { _ =>
      p.success {
        allOpenedResources.keys shouldNot contain("0")
        events should be(Seq("using 0"))
      }
    }
    p.future
  }

  "reference count test with shared -- async -- raise exception" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]

    val sharedResource: ResourceFactoryT[Future, FakeResource] =
      managed[Future, FakeResource](new FakeResource(allOpenedResources, "0")).shared

    val mappedResource: ResourceFactoryT[Future, Throwable \/ FakeResource] = sharedResource.map(\/.right)

    val mr = new EitherT[ResourceFactoryT[Future, ?], Throwable, FakeResource](mappedResource)

    val usingResource = mr.flatMap { r1: FakeResource =>
      events += "using 0"
      allOpenedResources("0") should be(r1)

      EitherT.eitherTMonadError[ResourceFactoryT[Future, ?], Throwable].raiseError[Assertion](new Boom)
    }

    val future: Future[Throwable \/ Assertion] = usingResource.run.run

    val p = Promise[Assertion]

    future.unsafePerformAsync { either =>
      inside(either) {
        case -\/(e) =>
          p.success {
            e should be(a[Boom])
            allOpenedResources.keys shouldNot contain("0")
            events should be(Seq("using 0"))
          }
      }
    }
    p.future
  }
  //
  //  "reference count test with shared -- ParallelFuture -- raise exception" in {
  //    val events = mutable.Buffer.empty[String]
  //    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
  //
  //    import Future.futureParallelApplicativeInstance
  //    val sharedResource: ResourceT[ParallelFuture, FakeResource] =
  //      managed[Future, FakeResource](new FakeResource(allOpenedResources, "0")).shared
  //        .asInstanceOf[ResourceT[ParallelFuture, FakeResource]]
  //
  //    val mappedResource: ResourceT[ParallelFuture, Throwable \/ FakeResource] = sharedResource.map(\/.right)
  //
  //    val mr = new EitherT[ResourceT[ParallelFuture, ?], Throwable, FakeResource](mappedResource)
  //
  //    val usingResource = mr.flatMap { r1: FakeResource =>
  //      events += "using 0"
  //      allOpenedResources("0") should be(r1)
  //
  //      EitherT
  //        .eitherTMonadError[ResourceT[Future, ?], Throwable]
  //        .raiseError[Assertion](new Boom)
  //        .asInstanceOf[EitherT[ResourceT[ParallelFuture, ?], Throwable, Assertion]]
  //    }
  //
  //    val future: ParallelFuture[Throwable \/ Assertion] = usingResource.run.run
  //
  //    val p = Promise[Assertion]
  //
  //    future.unsafePerformAsync { either =>
  //      inside(either) {
  //        case -\/(e) =>
  //          p.success {
  //            e should be(a[Boom])
  //            allOpenedResources.keys shouldNot contain("0")
  //            events should be(Seq("using 0"))
  //          }
  //      }
  //    }
  //    p.future
  //  }

}
