package org.animallink.animal.application;

import java.util.List;

public record CreatePostAdoptionCommand(String textContent,
                                        List<CreatePostCommand.MediaInput> media) {
}
