Developer notes
===============

Returned data structures should not include a top level 'error' field. This is
reserved for returning errors in JSON.

Templates are mustache templates.

Exception mapping
-----------------

in us.kbase.auth2.exceptions  
AuthException and subclasses other than the below - 400  
AuthenticationException and subclasses - 401  
UnauthorizedException and subclasses - 403  
NoDataException and subclasses - 404  

Anything else is mapped to 500.
