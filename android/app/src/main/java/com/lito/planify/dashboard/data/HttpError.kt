package com.lito.planify.dashboard.data

import retrofit2.Response
import java.io.IOException

/** Respuesta HTTP sin éxito (o sin cuerpo): el servidor responde, pero no con el dato. */
class HttpError(val code: Int) : IOException("HTTP $code")

fun <T> Response<T>.bodyOrThrow(): T = body().takeIf { isSuccessful } ?: throw HttpError(code())
