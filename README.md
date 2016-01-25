vertx-mirror
=============

A verticle that mirrors resources, which are provided as zip into a rest storage.

Provide resources as zip
------------------------
The zip has to be accessible over http, eg. http://host:8888/bla/blo/ble/resources.zip.
The zip can contain arbitrary resources (files), the file types are not relevant.
The resources (files) can be zipped in any path depth, the path will be mapped to the rest storage.

The API
--------
HTTP Method: `POST`  
URL: `http://localhost:8686/mirror`  
payload:   

    {
        "path": "relative/path/to/the/zip.zip" // The relative path (see config parameter mirrorRootPath) to the zip
    }



Configuration
-------------

    {
        "serverPort": 8686 // where the http server is listen on, standard is 8686
        "selfClientHost": "localhost" // where the verticle access the zip, standard is localhost
        "selfClientPort": 7012 // where the verticle access the zip, standard is 7012
        "mirrorHost": "localhost" // where the zip file entries are putted, standard is localhost
        "mirrorPort": "7012" // where the zip file entries are putted, standard is 7012
        "mirrorRootPath": "/root" // the root path, that is used to get the zip and to put the resources, standard is "/root"
    }