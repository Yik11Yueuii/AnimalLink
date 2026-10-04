package org.animallink.adoption.application;
import java.util.List;
public record PageResult<T>(List<T> items, int page, int size, long total) { }
