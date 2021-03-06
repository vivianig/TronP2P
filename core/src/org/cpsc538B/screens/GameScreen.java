package org.cpsc538B.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.viewport.StretchViewport;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import lombok.Getter;
import org.apache.commons.lang3.RandomUtils;
import org.cpsc538B.*;
import org.cpsc538B.go.GoSender;
import org.cpsc538B.input.TronInput;
import org.cpsc538B.model.Direction;
import org.cpsc538B.model.PositionAndDirection;
import org.cpsc538B.utils.GameUtils;

import java.util.*;

/**
 * Created by newmanne on 12/03/15.
 */
public class GameScreen extends ScreenAdapter {

    // resolution
    public static final int V_WIDTH = 1920;
    public static final int V_HEIGHT = 1080;

    public static final int UNOCCUPIED = 0;

    // grid dimensions
    public final static int GRID_WIDTH = 200;
    public final static int GRID_HEIGHT = 200;

    // display size of grid (how big each square is)
    public final static int GRID_SIZE = 10;

    // libgdx stuff
    private final TronP2PGame game;
    private final StretchViewport viewport;
    private TronInput tronInput;

    private Map<String, Label> pidToLabel = new HashMap<>();

    // game state
    private final Map<String, PositionAndDirection> playerPositions;
    private final int[][] grid;
    private final String pid;
    private int round;

    private float accumulator;

    private final Stage hud;

    private final Vector2[] wallVertices = new Vector2[]{
            new Vector2(0, 0),
            new Vector2(GRID_HEIGHT * GRID_SIZE, 0),
            new Vector2(GRID_WIDTH * GRID_SIZE, GRID_HEIGHT * GRID_SIZE),
            new Vector2(0, GRID_HEIGHT * GRID_SIZE),
            new Vector2(0, 0)
    };

    public static Map<String, Color> pidToColor;

    public GameScreen(TronP2PGame game, String pid, Map<String, PositionAndDirection> startingPositions) {
        this.game = game;
        this.pid = pid;
        grid = new int[GRID_WIDTH][GRID_HEIGHT];
        playerPositions = startingPositions;
        tronInput = new TronInput(getPositionAndDirection().getDirection());
        viewport = new StretchViewport(V_WIDTH, V_HEIGHT);
        round = 0;
        hud = new Stage(new StretchViewport(GameScreen.V_WIDTH, GameScreen.V_HEIGHT), game.getSpritebatch());
        final Table rootTable = new Table();
        rootTable.setFillParent(true);
        hud.addActor(rootTable);
        final Table hudTable = new Table();
        rootTable.add(hudTable).expand().left().top().padTop(GRID_SIZE * 3).padLeft(GRID_SIZE * 3);

        pidToColor = new HashMap<String, Color>();
        startingPositions.keySet().forEach(playerPid -> {
            pidToColor.put(playerPid, new Color(RandomUtils.nextFloat(0, 1.0f), RandomUtils.nextFloat(0, 1.0f), RandomUtils.nextFloat(0, 1.0f), 1.0f));
        });

        game.getNicknames().entrySet().forEach(entry -> {
            final Label nickname = new Label(entry.getValue(), game.getAssets().getLabelStyle());
            nickname.setColor(pidToColor.get(entry.getKey()));
            hudTable.add(nickname);
            pidToLabel.put(entry.getKey(), nickname);
            hudTable.row();
        });
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(tronInput);
    }

    @Override
    public void render(float delta) {
        accumulator += delta;
        final Collection<Object> goEvents = game.getGoSender().getGoEvents();

        for (Object event : goEvents) {
            if (event instanceof GoSender.RoundStartEvent) {
                round = ((GoSender.RoundStartEvent) event).getRound();
                game.getGoSender().sendToGo(new GoSender.MoveEvent(tronInput.getProvisionalDirection(), pid, round));
            } else if (event instanceof GoSender.MovesEvent) {
                // process move
                final ImmutableMap<String, PositionAndDirection> oldPositions = ImmutableMap.copyOf(playerPositions);
                final GoSender.MovesEvent movesEvent = (GoSender.MovesEvent) event;
                final List<Map<String, PositionAndDirection>> moves = movesEvent.getMoves();
                moves.forEach(roundMoves -> {
                    roundMoves.entrySet().forEach(entry -> {
                        final PositionAndDirection move = entry.getValue();
                        grid[move.getX()][move.getY()] = Integer.parseInt(entry.getKey());
                        playerPositions.put(entry.getKey(), move);
                    });
                });

                // Find dead players and mark them as such. A dead player is a player that has not moved
                Maps.difference(oldPositions, playerPositions).entriesInCommon().keySet().forEach(deadPid -> {
                    final Label label = pidToLabel.get(deadPid);
                    if (!label.getText().toString().endsWith("(DEAD)")) {
                        label.setText(label.getText() + " (DEAD)");
                    }
                });

            } else if (event instanceof GoSender.GameOverEvent) {
                final List<String> pidsInOrderOfDeath = ((GoSender.GameOverEvent) event).getPidsInOrderOfDeath();
                game.setScreen(new GameOverScreen(game, pidsInOrderOfDeath));
                break;
            } else {
                throw new IllegalStateException();
            }
        }
        GameUtils.clearScreen();


        // scroll
        viewport.getCamera().position.set(Math.min(GRID_WIDTH * GRID_SIZE - V_WIDTH / 2, Math.max(V_WIDTH / 2, getPositionAndDirection().getX() * GRID_SIZE)),
                Math.min(GRID_HEIGHT * GRID_SIZE - V_HEIGHT / 2, Math.max(V_HEIGHT / 2, getPositionAndDirection().getY() * GRID_SIZE)),
                0);

        // render
        viewport.apply();
        final ShapeRenderer shapeRenderer = game.getShapeRenderer();
        shapeRenderer.setProjectionMatrix(viewport.getCamera().combined);

        drawWalls(shapeRenderer);
        drawGrid(shapeRenderer);
        drawPlayers(shapeRenderer);
        hud.act(delta);
        hud.draw();
    }

    private void drawPlayers(ShapeRenderer shapeRenderer) {
        playerPositions.entrySet().stream().forEach(entry -> {
            shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
            shapeRenderer.setColor(Color.WHITE);
            final String playerPid = entry.getKey();
            final PositionAndDirection positionAndDirection = entry.getValue();
            shapeRenderer.rect(positionAndDirection.getX() * GRID_SIZE, positionAndDirection.getY() * GRID_SIZE, GRID_SIZE, GRID_SIZE);
            shapeRenderer.end();
            if (pid.equals(playerPid)) {
                shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
                shapeRenderer.circle(positionAndDirection.getX() * GRID_SIZE + GRID_SIZE / 2, positionAndDirection.getY() * GRID_SIZE + GRID_SIZE / 2, GRID_SIZE * 3);
                shapeRenderer.end();
            }
        });
        shapeRenderer.end();
    }

    // debug
    private void printGrid() {
        for (int[] row : grid) {
            Gdx.app.log(TronP2PGame.LOG_TAG, Arrays.toString(row));
        }
    }

    private PositionAndDirection getPositionAndDirection() {
        return playerPositions.get(pid);
    }

    private void drawGrid(ShapeRenderer shapeRenderer) {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < grid.length; i++) {
            for (int j = 0; j < grid[i].length; j++) {
                int square = grid[i][j];
                if (square != UNOCCUPIED) {
                    shapeRenderer.setColor(pidToColor.get(Integer.toString(square)));
                    shapeRenderer.rect(i * GRID_SIZE, j * GRID_SIZE, GRID_SIZE, GRID_SIZE);
                }
            }
        }
        shapeRenderer.end();
    }

    private void drawWalls(ShapeRenderer shapeRenderer) {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(Color.PINK);
        for (int i = 0; i < wallVertices.length - 1; i++) {
            shapeRenderer.rectLine(wallVertices[i], wallVertices[i + 1], GRID_SIZE * 2);
        }
        shapeRenderer.end();
    }

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height, true);
        // TODO: might need to resize fonts here
    }

}
