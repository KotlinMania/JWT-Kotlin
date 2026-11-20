package consumer

import io.github.kotlinmania.androidsystemproperties.AndroidSystemProperties
import io.github.kotlinmania.asynctrait.InferredBound
import io.github.kotlinmania.asynctrait.asyncTrait
import io.github.kotlinmania.asynctrait.asyncTraitOrThrow
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.quote.ToTokens
import io.github.kotlinmania.quote.quote
import io.github.kotlinmania.syn.TokenStreamParseResult

fun expandedResult(): TokenStreamParseResult =
    asyncTrait(quote(""), quote("trait Consumer { async fn run(&self); }"))

fun expandedTokens(): TokenStream =
    asyncTraitOrThrow(quote(""), quote("trait Consumer { async fn run(&self); }"))

fun inferredBound(): ToTokens = InferredBound.Send

fun invalidProperty(): String? = AndroidSystemProperties.new().get("ro.product\u0000model")
