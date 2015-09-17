# vertx-clustered-session-handler-issue
Demonstration to illustrate a race condition which exists in the Vertx 3.1.0-SNAPSHOT Clustered Session Store/Session Handler.

Appears to be a race condition when the Session handler sets up a routingContext.addHeadersEndHandler that completes asynchronously. Corruption appears to happen when writing occurs to the response while waiting for the addHeadersEndHandler future to complete (in this case the stream is being written to by a Pump, but also occurs with normal writing - i.e. chunked response).

The demonstration uses a proxy setup to display a number of images (the Google logo) so the corruption of the stream is easy to spot.

To run (with gradle installed):
``` 
gradle run  
```
or Linux based without Gradle install
``` 
./gradlew run  
```
or Windows based without Gradle install
``` 
gradlew.bat run  
```

Once loaded, navigate to:
[http://127.0.0.1:8080/](http://127.0.0.1:8080/)

The loading screen will show 3 options: 
  * Standard session handler with Local Session Store (works - no race condition)
  * Standard session handler with Clustered Session Store (fails - race condition occurs when session is being updated in addStoreSessionHandler
  * Modified Session handler with Clustered Session Store (works - removes race condition from the above combination)

## Load screen
![Load screen](https://raw.githubusercontent.com/cdjones32/vertx-clustered-session-handler-issue/master/src/main/resources/load_screen.png)

## With no issue
![No issue](https://raw.githubusercontent.com/cdjones32/vertx-clustered-session-handler-issue/master/src/main/resources/no_isue.png)

## With issue
![Issue](https://raw.githubusercontent.com/cdjones32/vertx-clustered-session-handler-issue/master/src/main/resources/issue.png)
