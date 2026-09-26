# PostsApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**createPost**](PostsApi.md#createPost) | **POST** api/v1/posts |  |
| [**deletePost**](PostsApi.md#deletePost) | **DELETE** api/v1/posts/{id} |  |
| [**getPost**](PostsApi.md#getPost) | **GET** api/v1/posts/{id} |  |
| [**listPosts**](PostsApi.md#listPosts) | **GET** api/v1/posts |  |
| [**renamePost**](PostsApi.md#renamePost) | **PATCH** api/v1/posts/{id} |  |





### Example
```kotlin
// Import classes:
//import cn.hedgeho9.murmur.api.*
//import cn.hedgeho9.murmur.api.infrastructure.*
//import cn.hedgeho9.murmur.api.models.*

val apiClient = ApiClient()
apiClient.setBearerToken("TOKEN")
val webService = apiClient.createWebservice(PostsApi::class.java)
val idempotencyKey : kotlin.String = idempotencyKey_example // kotlin.String | 
val createPostRequest : CreatePostRequest =  // CreatePostRequest | 

launch(Dispatchers.IO) {
    val result : CreatedPost = webService.createPost(idempotencyKey, createPostRequest)
}
```

### Parameters
| **idempotencyKey** | **kotlin.String**|  | |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **createPostRequest** | [**CreatePostRequest**](CreatePostRequest.md)|  | |

### Return type

[**CreatedPost**](CreatedPost.md)

### Authorization


Configure bearerAuth:
    ApiClient().setBearerToken("TOKEN")

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json




### Example
```kotlin
// Import classes:
//import cn.hedgeho9.murmur.api.*
//import cn.hedgeho9.murmur.api.infrastructure.*
//import cn.hedgeho9.murmur.api.models.*

val apiClient = ApiClient()
apiClient.setBearerToken("TOKEN")
val webService = apiClient.createWebservice(PostsApi::class.java)
val id : java.util.UUID = 38400000-8cf0-11bd-b23e-10b96e4ef00d // java.util.UUID | 

launch(Dispatchers.IO) {
    webService.deletePost(id)
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **id** | **java.util.UUID**|  | |

### Return type

null (empty response body)

### Authorization


Configure bearerAuth:
    ApiClient().setBearerToken("TOKEN")

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json




### Example
```kotlin
// Import classes:
//import cn.hedgeho9.murmur.api.*
//import cn.hedgeho9.murmur.api.infrastructure.*
//import cn.hedgeho9.murmur.api.models.*

val apiClient = ApiClient()
apiClient.setBearerToken("TOKEN")
val webService = apiClient.createWebservice(PostsApi::class.java)
val id : java.util.UUID = 38400000-8cf0-11bd-b23e-10b96e4ef00d // java.util.UUID | 

launch(Dispatchers.IO) {
    val result : PostDetail = webService.getPost(id)
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **id** | **java.util.UUID**|  | |

### Return type

[**PostDetail**](PostDetail.md)

### Authorization


Configure bearerAuth:
    ApiClient().setBearerToken("TOKEN")

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json




### Example
```kotlin
// Import classes:
//import cn.hedgeho9.murmur.api.*
//import cn.hedgeho9.murmur.api.infrastructure.*
//import cn.hedgeho9.murmur.api.models.*

val apiClient = ApiClient()
apiClient.setBearerToken("TOKEN")
val webService = apiClient.createWebservice(PostsApi::class.java)
val limit : kotlin.Int = 56 // kotlin.Int | 
val cursor : kotlin.String = cursor_example // kotlin.String | 

launch(Dispatchers.IO) {
    val result : PostPage = webService.listPosts(limit, cursor)
}
```

### Parameters
| **limit** | **kotlin.Int**|  | [optional] [default to 20] |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **cursor** | **kotlin.String**|  | [optional] |

### Return type

[**PostPage**](PostPage.md)

### Authorization


Configure bearerAuth:
    ApiClient().setBearerToken("TOKEN")

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json




### Example
```kotlin
// Import classes:
//import cn.hedgeho9.murmur.api.*
//import cn.hedgeho9.murmur.api.infrastructure.*
//import cn.hedgeho9.murmur.api.models.*

val apiClient = ApiClient()
apiClient.setBearerToken("TOKEN")
val webService = apiClient.createWebservice(PostsApi::class.java)
val id : java.util.UUID = 38400000-8cf0-11bd-b23e-10b96e4ef00d // java.util.UUID | 
val renamePostRequest : RenamePostRequest =  // RenamePostRequest | 

launch(Dispatchers.IO) {
    val result : Post = webService.renamePost(id, renamePostRequest)
}
```

### Parameters
| **id** | **java.util.UUID**|  | |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **renamePostRequest** | [**RenamePostRequest**](RenamePostRequest.md)|  | |

### Return type

[**Post**](Post.md)

### Authorization


Configure bearerAuth:
    ApiClient().setBearerToken("TOKEN")

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json

