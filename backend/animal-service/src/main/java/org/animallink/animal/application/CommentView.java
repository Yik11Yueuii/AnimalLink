package org.animallink.animal.application;

import org.animallink.animal.domain.Comment;

public record CommentView(Comment comment, AuthorSummary author) {
}
