package com.cloudant.clouseau;

@FunctionalInterface
interface SafeSearch<T> {

    T search() throws Exception;

}
