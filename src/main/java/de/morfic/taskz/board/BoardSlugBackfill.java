package de.morfic.taskz.board;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Gives boards created before slugs existed their slug on startup. */
@Component
class BoardSlugBackfill implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(BoardSlugBackfill.class);

    private final BoardService boardService;

    BoardSlugBackfill(BoardService boardService) {
        this.boardService = boardService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int filled = boardService.fillMissingSlugs();
        if (filled > 0) {
            LOG.info("Added slugs to {} boards", filled);
        }
    }
}
