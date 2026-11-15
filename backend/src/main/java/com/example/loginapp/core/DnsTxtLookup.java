package com.example.loginapp.core;

import java.util.List;

/** Reads the TXT records of a DNS name. A seam so tests need no network. An unknown name or an unreachable resolver gives an empty list. */
public interface DnsTxtLookup {

    List<String> txt(String name);
}
