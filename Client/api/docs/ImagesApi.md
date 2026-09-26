# ImagesApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**completeImageUpload**](ImagesApi.md#completeImageUpload) | **POST** api/v1/images/{id}/complete |  |
| [**createImageUpload**](ImagesApi.md#createImageUpload) | **POST** api/v1/images/uploads |  |
| [**getImageUrl**](ImagesApi.md#getImageUrl) | **GET** api/v1/images/{id}/url |  |





### Example
```kotlin
// Import classes:
//import cn.hedgeho9.myapp.api.*
//import cn.hedgeho9.myapp.api.infrastructure.*
//import cn.hedgeho9.myapp.api.models.*

val apiClient = ApiClient()
apiClient.setBearerToken("TOKEN")
val webService = apiClient.createWebservice(ImagesApi::class.java)
val id : java.util.UUID = 38400000-8cf0-11bd-b23e-10b96e4ef00d // java.util.UUID | 

launch(Dispatchers.IO) {
    val result : Image = webService.completeImageUpload(id)
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **id** | **java.util.UUID**|  | |

### Return type

[**Image**](Image.md)

### Authorization


Configure bearerAuth:
    ApiClient().setBearerToken("TOKEN")

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json




### Example
```kotlin
// Import classes:
//import cn.hedgeho9.myapp.api.*
//import cn.hedgeho9.myapp.api.infrastructure.*
//import cn.hedgeho9.myapp.api.models.*

val apiClient = ApiClient()
apiClient.setBearerToken("TOKEN")
val webService = apiClient.createWebservice(ImagesApi::class.java)
val idempotencyKey : kotlin.String = idempotencyKey_example // kotlin.String | 
val uploadRequest : UploadRequest =  // UploadRequest | 

launch(Dispatchers.IO) {
    val result : UploadResponse = webService.createImageUpload(idempotencyKey, uploadRequest)
}
```

### Parameters
| **idempotencyKey** | **kotlin.String**|  | |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **uploadRequest** | [**UploadRequest**](UploadRequest.md)|  | |

### Return type

[**UploadResponse**](UploadResponse.md)

### Authorization


Configure bearerAuth:
    ApiClient().setBearerToken("TOKEN")

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json




### Example
```kotlin
// Import classes:
//import cn.hedgeho9.myapp.api.*
//import cn.hedgeho9.myapp.api.infrastructure.*
//import cn.hedgeho9.myapp.api.models.*

val apiClient = ApiClient()
apiClient.setBearerToken("TOKEN")
val webService = apiClient.createWebservice(ImagesApi::class.java)
val id : java.util.UUID = 38400000-8cf0-11bd-b23e-10b96e4ef00d // java.util.UUID | 

launch(Dispatchers.IO) {
    val result : ImageUrl = webService.getImageUrl(id)
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **id** | **java.util.UUID**|  | |

### Return type

[**ImageUrl**](ImageUrl.md)

### Authorization


Configure bearerAuth:
    ApiClient().setBearerToken("TOKEN")

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

