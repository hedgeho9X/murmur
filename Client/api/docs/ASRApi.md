# ASRApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**createAsrCredentials**](ASRApi.md#createAsrCredentials) | **POST** api/v1/asr/credentials |  |
| [**streamAsr**](ASRApi.md#streamAsr) | **GET** api/v1/asr/stream |  |





### Example
```kotlin
// Import classes:
//import cn.hedgeho9.murmur.api.*
//import cn.hedgeho9.murmur.api.infrastructure.*
//import cn.hedgeho9.murmur.api.models.*

val apiClient = ApiClient()
apiClient.setBearerToken("TOKEN")
val webService = apiClient.createWebservice(ASRApi::class.java)

launch(Dispatchers.IO) {
    val result : AsrCredentials = webService.createAsrCredentials()
}
```

### Parameters
This endpoint does not need any parameter.

### Return type

[**AsrCredentials**](AsrCredentials.md)

### Authorization


Configure bearerAuth:
    ApiClient().setBearerToken("TOKEN")

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json




WebSocket: start {format:pcm_s16le,sample_rate:16000,channels:1} → ready → binary PCM → finish → transcript/completed. Transcript fields: segment_id,text,is_final. Error: type&#x3D;error,code,message.

### Example
```kotlin
// Import classes:
//import cn.hedgeho9.murmur.api.*
//import cn.hedgeho9.murmur.api.infrastructure.*
//import cn.hedgeho9.murmur.api.models.*

val apiClient = ApiClient()
apiClient.setBearerToken("TOKEN")
val webService = apiClient.createWebservice(ASRApi::class.java)

launch(Dispatchers.IO) {
    webService.streamAsr()
}
```

### Parameters
This endpoint does not need any parameter.

### Return type

null (empty response body)

### Authorization


Configure bearerAuth:
    ApiClient().setBearerToken("TOKEN")

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: Not defined

