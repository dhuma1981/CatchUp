/*
 * Copyright (c) 2017 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.sweers.catchup.service.designernews

import com.serjltt.moshi.adapters.Wrapped
import com.squareup.moshi.Moshi
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.functions.BiFunction
import io.sweers.catchup.service.api.DataRequest
import io.sweers.catchup.service.api.DataResult
import io.sweers.catchup.service.api.LinkHandler
import io.sweers.catchup.service.api.Service
import io.sweers.catchup.service.api.ServiceKey
import io.sweers.catchup.service.api.ServiceMeta
import io.sweers.catchup.service.api.ServiceMetaKey
import io.sweers.catchup.service.api.TextService
import io.sweers.catchup.service.designernews.model.Story
import io.sweers.catchup.service.designernews.model.User
import io.sweers.catchup.util.collect.toCommaJoinerList
import io.sweers.catchup.util.data.adapters.ISO8601InstantAdapter
import okhttp3.OkHttpClient
import org.threeten.bp.Instant
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Inject
import javax.inject.Qualifier

@Qualifier
private annotation class InternalApi

internal class DesignerNewsService @Inject constructor(
    @InternalApi private val serviceMeta: ServiceMeta,
    private val api: DesignerNewsApi,
    private val linkHandler: LinkHandler)
  : TextService {

  override fun meta() = serviceMeta

  override fun fetchPage(request: DataRequest): Maybe<DataResult> {
    val page = request.pageId.toInt()
    return api.getTopStories(page)
        .flatMapObservable { stories ->
          Observable.zip(
              // TODO This needs to update to the new /users endpoint behavior, which will only give a best effort result and not necessarily all
              Observable.fromIterable(stories),
              Observable.fromIterable(stories)
                  .map { it.links().user() }
                  .toList()
                  .flatMap { ids -> api.getUsers(ids.toCommaJoinerList()) }
                  .onErrorReturn { (0..stories.size).map { User.NONE } }
                  .flattenAsObservable { it },
              // RxKotlin might help here
              BiFunction<Story, User, StoryAndUserHolder> { story, user ->
                StoryAndUserHolder(story, if (user === User.NONE) null else user)
              })
        }
        .map { (story, user) ->
          with(story) {
            io.sweers.catchup.service.api.CatchUpItem(
                id = java.lang.Long.parseLong(id()),
                title = title(),
                score = "▲" to voteCount(),
                timestamp = createdAt(),
                author = user?.displayName(),
                source = hostname(),
                commentCount = commentCount(),
                tag = badge(),
                itemClickUrl = url(),
                itemCommentClickUrl = href()
                    .replace("api.", "www.")
                    .replace("api/v2/", "")
            )
          }
        }
        .toList()
        .map { DataResult(it, (page + 1).toString()) }
        .toMaybe()
  }

  override fun linkHandler() = linkHandler
}

@Module
abstract class DesignerNewsModule {

  @IntoMap
  @ServiceMetaKey(SERVICE_KEY)
  @Binds
  internal abstract fun designerNewsServiceMeta(@InternalApi meta: ServiceMeta): ServiceMeta

  @IntoMap
  @ServiceKey(SERVICE_KEY)
  @Binds
  internal abstract fun designerNewsService(service: DesignerNewsService): Service

  @Module
  companion object {

    private const val SERVICE_KEY = "dn"

    @InternalApi
    @Provides
    @JvmStatic
    internal fun provideDesignerNewsMeta() = ServiceMeta(
        SERVICE_KEY,
        R.string.dn,
        R.color.dnAccent,
        R.drawable.logo_dn,
        pagesAreNumeric = true,
        firstPageKey = "0"
    )

    @Provides
    @InternalApi
    @JvmStatic
    internal fun provideDesignerNewsMoshi(moshi: Moshi): Moshi {
      return moshi.newBuilder()
          .add(DesignerNewsAdapterFactory.create())
          .add(Instant::class.java, ISO8601InstantAdapter())
          .add(Wrapped.ADAPTER_FACTORY)
          .build()
    }

    @Provides
    @JvmStatic
    internal fun provideDesignerNewsService(client: Lazy<OkHttpClient>,
        @InternalApi moshi: Moshi,
        rxJavaCallAdapterFactory: RxJava2CallAdapterFactory): DesignerNewsApi {

      val retrofit = Retrofit.Builder().baseUrl(
          DesignerNewsApi.ENDPOINT)
          .callFactory { client.get().newCall(it) }
          .addCallAdapterFactory(rxJavaCallAdapterFactory)
          .addConverterFactory(MoshiConverterFactory.create(moshi))
          .validateEagerly(BuildConfig.DEBUG)
          .build()
      return retrofit.create(DesignerNewsApi::class.java)
    }
  }
}

private data class StoryAndUserHolder(val story: Story, val user: User?)