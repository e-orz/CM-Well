name := "cmwell-stortill"

libraryDependencies ++= {
  val dm = dependenciesManager.value
  Seq(
    dm("com.typesafe.akka", "akka-stream"),
    dm("com.lightbend.akka", "akka-stream-alpakka-cassandra"),
    dm("org.rogach", "scallop")
  )
}

packMain := Map(
  "addProtocol"     -> "cmwell.operations.modifier.AddProtocolField",
  "verifyProtocol"  -> "cmwell.operations.modifier.VerifyProtocolField",
  "fixType"         -> "cmwell.operations.modifier.FixType"
)

fullTest := (test in Test).value