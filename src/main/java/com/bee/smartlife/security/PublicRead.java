package com.bee.smartlife.security;

import java.lang.annotation.*;

/** Only GET handlers carrying this annotation bypass the login requirement. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicRead {}
