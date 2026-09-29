// `JsonFormat.Parser.usingRecursionLimit` is package-private in
// protobuf-java-util ("for testing only"), and its default of 100 message
// levels is below what the IR specification §4 requires a reader to accept.
// This one function, in the parser's own package, is the only access to it;
// the jar runs on the class path, where a split package is legal.
package com.google.protobuf.util

internal fun JsonFormat.Parser.withRecursionLimit(limit: Int): JsonFormat.Parser = usingRecursionLimit(limit)
