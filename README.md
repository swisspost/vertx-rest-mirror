vertx-mirror
=============

[![Build Status](https://travis-ci.com/swisspush/vertx-rest-mirror.svg?branch=master)](https://travis-ci.com/swisspush/vertx-rest-mirror)
[![codecov](https://codecov.io/gh/swisspost/vertx-rest-mirror/branch/master/graph/badge.svg?token=14IWfjaJYm)](https://codecov.io/gh/swisspost/vertx-rest-mirror)
[![Maven Central](https://img.shields.io/maven-central/v/org.swisspush/rest-mirror.svg)]()

A verticle that mirrors resources, which are provided as zip into a rest storage.

Provide resources as zip
------------------------
The zip has to be accessible over http, eg. http://host:8888/bla/blo/ble/resources.zip.
The zip can contain arbitrary resources (files), the file types are not relevant.
The resources (files) can be zipped in any path depth, the path will be mapped to the rest storage.

The API
--------
> POST http://localhost:8686/mirror  

Payload:   

    {
        "path": "relative/path/to/the/zip.zip" // Relative path (see config parameter mirrorRootPath) to the zip
        ["content-type": "application/json"]  // optional - content-type of the content elements in zip file
    }


Configuration
-------------

    {
        "serverPort": 8686 // where the http server is listen on, standard is 8686
        "selfClientHost": "localhost" // where the verticle access the zip, standard is localhost
        "selfClientPort": 7012 // where the verticle access the zip, standard is 7012
        "mirrorHost": "localhost" // where the zip file entries are putted, standard is localhost
        "mirrorPort": 7012 // where the zip file entries are putted, standard is 7012
        "mirrorRootPath": "/root" // the root path, that is used to get the zip and to put the resources, standard is "/root"
        "internalRequestHeaders": // Array of arrays holding request headers which are added to all outgoing requests
    }

Example:
```
{
    "serverPort": 8686,
    "selfClientHost": "localhost",
    "selfClientPort": 7012,
    "mirrorHost": "localhost",
    "mirrorPort": 7012,
    "mirrorRootPath": "/root",
    "internalRequestHeaders": [["x-foo", "bar"], ["x-bar", "zzz"]]
}
```
    
Dependencies
------------

- Versions 2.2.x (and later) of Rest-Mirror require **Java 11**.

- Versions 2.1.14 (and later) of Rest-Mirror depend on org.swisspush.rest-storage

- Versions 02.xx.xx (and later) of Rest-Mirror depend on Vert.x v3.2.0, therefore **Java 8** is required
