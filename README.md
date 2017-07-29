# RAII.scala <a href="http://thoughtworks.com/"><img align="right" src="https://www.thoughtworks.com/imgs/tw-logo.png" title="ThoughtWorks" height="15"/></a>

[![Build Status](https://travis-ci.org/ThoughtWorksInc/RAII.scala.svg?branch=master)](https://travis-ci.org/ThoughtWorksInc/RAII.scala)
[![Latest version](https://index.scala-lang.org/thoughtworksinc/raii.scala/asynchronous/latest.svg)](https://index.scala-lang.org/thoughtworksinc/raii.scala/asynchronous)
[![Scaladoc](https://javadoc.io/badge/com.thoughtworks.raii/asynchronous_2.11.svg?label=scaladoc)](https://javadoc.io/page/com.thoughtworks.raii/asynchronous_2.11/latest/com/thoughtworks/raii/package.html)

**RAII.scala** is a collection of utilities aims to manage native resources in [Scalaz](http://scalaz.org).

## Asynchronous `Do`

An `asynchronous.Do` is an asynchronous value, like `scala.concurrent.Future` or `scalaz.concurrent.Task`.
The difference is that resources in `Do` can be either automatically acquired/released in scope,
or managed by reference counting mechanism.

To use `Do`, add the following setting to your build.sbt,
             
``` scala
libraryDependencies += "com.thoughtworks.raii" %% "asynchronous" % "latest.release"
```

and check the [Scaladoc](https://javadoc.io/page/com.thoughtworks.raii/asynchronous_2.11/latest/com/thoughtworks/raii/asynchronous$$Do.html) for usage.

## `ResourceT`

`Do` consists of some monad transformers.
The ability of resource management in `Do` is provided by the monad transformer `ResourceT`.

You can combine `ResourceT` with monads other than `asynchronous.Do`. For example, a resource manager in synchronous execution.

### Covariant `ResourceT`

To use `ResourceT` for monadic data types whose kind is `F[+A]`(e.g. `scalaz.concurrent.Future` or `scalaz.Name`),
add the following setting to your build.sbt:

``` scala
libraryDependencies += "com.thoughtworks.raii" %% "covariant" % "latest.release"
```

and check the [Scaladoc](https://javadoc.io/page/com.thoughtworks.raii/covariant_2.11/latest/com/thoughtworks/raii/covariant$$ResourceT.html) for usage.

### Invariant `ResourceT`

To use `ResourceT` for monadic data types whose kind is `F[A]`(e.g. `scalaz.effect.IO`),
add the following setting to your build.sbt:

 
``` scala
libraryDependencies += "com.thoughtworks.raii" %% "invariant" % "latest.release"
```

and check the [Scaladoc](https://javadoc.io/page/com.thoughtworks.raii/invariant_2.11/latest/com/thoughtworks/raii/invariant$$ResourceT.html) for usage.

## Links

* [API Documentation](https://javadoc.io/page/com.thoughtworks.raii/asynchronous_2.11/latest/com/thoughtworks/raii/package.html)

### Related projects

* [Scalaz](http://scalaz.org/) provides type classes and underlying data structures for this project.
* [ThoughtWorks Each](https://github.com/ThoughtWorksInc/each) provides `monadic`/`each`-like syntax which can be used with this project.
* [tryt.scala](https://github.com/ThoughtWorksInc/TryT.scala) provides exception handling monad transformers for this project.
* [future.scala](https://github.com/ThoughtWorksInc/future.scala) provides the asynchronous task types for this project.
* [DeepLearning.scala](http://deeplearning.thoughtworks.school/) uses this project for asynchronous executed neural networks.

## Credits

This library is inspired by [Josh Suereth](https://github.com/jsuereth)'s [scala-arm](https://github.com/jsuereth/scala-arm),
in which I implemented the reference counting mechanism at first.
