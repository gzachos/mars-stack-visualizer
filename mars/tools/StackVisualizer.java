package mars.tools;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Observable;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;

import mars.Globals;
import mars.ProgramStatement;
import mars.assembler.Symbol;
import mars.assembler.SymbolTable;
import mars.mips.hardware.AccessNotice;
import mars.mips.hardware.AddressErrorException;
import mars.mips.hardware.Memory;
import mars.mips.hardware.MemoryAccessNotice;
import mars.mips.hardware.Register;
import mars.mips.hardware.RegisterAccessNotice;
import mars.mips.hardware.RegisterFile;
import mars.mips.instructions.Instruction;
import mars.simulator.BackStepper;
import mars.util.Binary;
import mars.venus.VenusUI;

@SuppressWarnings({ "serial" })
public class StackVisualizer extends AbstractMarsToolAndApplication {

	private static String name        = "Stack Visualizer";
	private static String versionID   = "1.0";
	private static String version     = "Version " + versionID + " (George Z. Zachos, Petros Manousis)";
	private static String heading     = "Visualizing Stack Modification Operations";
	private static String releaseDate = "30-Sep-2019";

	// We need the following definition here to initialize numberOfColumns
	private final String[] colNamesWhenDataPerByte = {"Address", "+3", "+2", "+1", "+0", "Stored Reg", "Call Layout"};
	private final String[] colNamesWhenNotDataPerByte = {"Address", "Word-length Data", "Stored Reg", "Call Layout"};

	/*
	 * Memory.stackBaseAddress:  word-aligned
	 * Memory.stackLimitAddress: word-aligned
	 * Max stack address value:  Memory.stackBaseAddress + (WORD_LENGTH_BYTES-1)
	 * Min stack address value:  Memory.stackLimitAddress
	 *
	 * Stack grows towards lower addresses: .stackBaseAddress > .stackLimitAddress
	 * Word-length operations can take place in both .stackBaseAddress and .stackLimitAddress
	 */
	private final int     SP_REG_NUMBER             = RegisterFile.STACK_POINTER_REGISTER;
	private final int     SP_INIT_ADDR              = Memory.stackPointer;
	private final Memory  memInstance               = Memory.getInstance();
	private final boolean endianness                = memInstance.getByteOrder();
	private final boolean LITTLE_ENDIAN             = Memory.LITTLE_ENDIAN;
	private final int     WORD_LENGTH_BYTES         = Memory.WORD_LENGTH_BYTES;
	private final int     WORD_LENGTH_BITS          = WORD_LENGTH_BYTES * 8;
	private final int     I_RS_OPERAND_LIST_INDEX   = 0; // I-format RS (source register) index
	private final int     J_ADDR_OPERAND_LIST_INDEX = 0; // J-format Address index
	private final int     R_RS_OPERAND_LIST_INDEX   = 0; // R-format RS (source register) index
	private final int     INITIAL_MAX_SP_VALUE      = SP_INIT_ADDR + (WORD_LENGTH_BYTES - 1); // Max byte address
	private int           maxSpValue                = INITIAL_MAX_SP_VALUE;
	private int           maxSpValueWordAligned     = SP_INIT_ADDR;
	private String        regNameToBeStoredInStack  = null;
	private String        frameNameToBeCreated      = null;
	private final ArrayList<Integer> ras            = new ArrayList<Integer>(); // Return Address Stack
	private final ActiveFunctionCallStats activeFunctionCallStats = new ActiveFunctionCallStats();

	// GUI-Related fields
	private final VenusUI marsGui                   = Globals.getGui();
	private final int     windowWidth               = 600;
	private final int     windowHeight              = 600;
	private final int     ADDRESS_COLUMN            = 0;  // Should always be in first column.
	private final int     FIRST_BYTE_COLUMN         = 1;  // Should always be in second column.
	private final int     LAST_BYTE_COLUMN          = FIRST_BYTE_COLUMN + (WORD_LENGTH_BYTES - 1);
	private final int     WORD_COLUMN               = 1;
	private final int     INITIAL_ROW_COUNT         = 30;
	private int           numberOfColumns           = colNamesWhenDataPerByte.length;
	private int           numberOfRows              = -1;
	private final int     frameNameColOffsetFromEnd = 1;
	private final int     storedRegColOffsetFromEnd = 2;
	private int           storedRegisterColumn      = numberOfColumns - storedRegColOffsetFromEnd;
	private int           frameNameColumn           = numberOfColumns - frameNameColOffsetFromEnd;
	private final int     REMAINING_ROWS_THRESHOLD  = 5;
	private int           spDataRowIndex            = 0;
	private int           spDataColumnIndex         = LAST_BYTE_COLUMN;
	private JTable        table;
	private JPanel        panel;
	private JScrollPane   scrollPane;
	private JCheckBox     dataPerByte;
	private JCheckBox     hexAddressesCheckBox;
	private JCheckBox     hexValuesCheckBox;
	private final int     LIGHT_YELLOW = 0xFFFF99;
	private final int     LIGHT_ORANGE = 0xFFC266;
	private final int     LIGHT_GRAY   = 0xE0E0E0;
	private final int     GRAY         = 0x999999;
	private final int     WHITE        = 0xFFFFFF;
	private boolean       disabledBackStep = false;
	private final DefaultTableModel tableModel = new DefaultTableModel();

	// Debug-related data
	private final boolean debug = false, printMemContents = false, debugBackStepper = false;
	private final boolean displayDataPerByte = false;

	protected StackVisualizer(String title, String heading) {
		super(title, heading);
	}

	public StackVisualizer() {
		super(StackVisualizer.name + ", " + StackVisualizer.version, StackVisualizer.heading);
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	protected JComponent buildMainDisplayArea() {
		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.gridx = 0;
		c.weightx = 1.0;
		c.gridy = 0;
		c.weighty = 1.0;
		panel = new JPanel(new GridBagLayout());
		panel.setPreferredSize(new Dimension(windowWidth, windowHeight));
		for (String s : colNamesWhenDataPerByte)
			tableModel.addColumn(s);
		table = new JTable(tableModel);
		table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		table.getTableHeader().setReorderingAllowed(false);
		table.setEnabled(false);
		table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
			@Override
			public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
				final Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
				int color = WHITE;
				if (row == spDataRowIndex) {
					color = LIGHT_YELLOW;
					// $sp cell coloring doesn't work with user column reordering
					if (dataPerByte.isSelected() && column == spDataColumnIndex)
						color = LIGHT_ORANGE;
				}
				else if (row > spDataRowIndex) {
					color = GRAY;
				}
				else if (row % 2 == 0) {
						color = LIGHT_GRAY;
				}
				c.setBackground(new Color(color));

				if ((dataPerByte.isSelected() && column >= FIRST_BYTE_COLUMN && column <= LAST_BYTE_COLUMN) ||
						(!dataPerByte.isSelected() && column == WORD_COLUMN))
					setHorizontalAlignment(SwingConstants.RIGHT);
				else if (column == storedRegisterColumn || column == frameNameColumn)
					setHorizontalAlignment(SwingConstants.CENTER);
				else
					setHorizontalAlignment(SwingConstants.LEFT);
				return c;
			}
		});
		// table.setAutoResizeMode( JTable.AUTO_RESIZE_OFF );
		resizeTableColumns(true);

		scrollPane = new JScrollPane(table);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
		scrollPane.setVisible(true);
		panel.add(scrollPane, c);
		table.setFillsViewportHeight(true);

		c.gridy++;	// change line
		c.weightx = 1.0;
		c.weighty = 0;
		dataPerByte = new JCheckBox("Display data per byte");
		dataPerByte.setSelected(true);
		panel.add(dataPerByte, c);
		dataPerByte.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if(dataPerByte.isSelected() == false)
					transformTableModel(colNamesWhenNotDataPerByte);
				else
					transformTableModel(colNamesWhenDataPerByte);
			}
		});

		c.gridy++;	// change line
		hexAddressesCheckBox = new JCheckBox("Hexadecimal Addresses");
		hexAddressesCheckBox.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				getStackData();
				table.repaint();
			}
		});
		hexAddressesCheckBox.setSelected(true);
		panel.add(hexAddressesCheckBox, c);

		c.gridy++;	// change line
		hexValuesCheckBox = new JCheckBox("Hexadecimal Values");
		hexValuesCheckBox.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				getStackData();
				table.repaint();
			}
		});
		hexValuesCheckBox.setSelected(true);
		panel.add(hexValuesCheckBox, c);
		return panel;
	}

	private void resizeTableColumns(boolean dataPerByte) {
		TableColumnModel columnModel = table.getColumnModel();
		for (int colIndex = 0 ; colIndex < columnModel.getColumnCount(); colIndex++) {
			TableColumn col = columnModel.getColumn(colIndex);
			int min, pref;
			min = pref = 75;
			if (dataPerByte) {
				if (colIndex >= FIRST_BYTE_COLUMN && colIndex <= LAST_BYTE_COLUMN) {
					min = 25;
					pref = 50;
				} else if (colIndex == ADDRESS_COLUMN) {
					min = 75;
					pref = 100;
				} else if (colIndex == storedRegisterColumn) {
					min = 25;
					pref = 75;
				} else if (colIndex == frameNameColumn) {
					min = 25;
					pref = 150;
				}
			} else {
				if (colIndex == frameNameColumn) {
					min = 25;
					pref = 150;
				}
			}
			col.setMinWidth(min);
			col.setPreferredWidth(pref);
		}
	}

	private void transformTableModel(String columnNames[]) {
		Object storedRegColumnData[] = new Object[numberOfRows];
		Object frameNameColumnData[] = new Object[numberOfRows];

		// Backup toredRegister and frameName columns
		for (int row = 0; row < numberOfRows; row++) {
			storedRegColumnData[row] = tableModel.getValueAt(row, storedRegisterColumn);
			frameNameColumnData[row] = tableModel.getValueAt(row, frameNameColumn);
		}

		table.setVisible(false);   // Used to avoid rendering delays
		tableModel.setColumnCount(0);	// Clear table columns
		for (String s : columnNames)
			tableModel.addColumn(s);	// Add new table columns

		// Update table-related data
		numberOfColumns = tableModel.getColumnCount();
		numberOfRows = tableModel.getRowCount();
		storedRegisterColumn = numberOfColumns - storedRegColOffsetFromEnd;
		frameNameColumn = numberOfColumns - frameNameColOffsetFromEnd;
		resizeTableColumns(dataPerByte.isSelected());

		// Restore toredRegister and frameName columns
		for (int row = 0; row < numberOfRows; row++) {
			tableModel.setValueAt(storedRegColumnData[row], row, storedRegisterColumn);
			tableModel.setValueAt(frameNameColumnData[row], row, frameNameColumn);
		}
		getStackData();
		table.repaint();
		table.setVisible(true);
	}

	@Override
	protected void initializePreGUI() {
		// TODO: disable reset button too?

		// TODO: disable actions performed when Tool not connected!
		marsGui.getRunAssembleItem().addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (!isObserving()) // TODO verify
					return;

				if (debugBackStepper) {
					System.out.flush();
					System.err.println("RunAssemble");
					System.err.flush();
				}
				runButtonsSetEnabled(true);
				reset();
			}
		});

		marsGui.getAssembleButton().addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (!isObserving()) // TODO verify
					return;

				if (debugBackStepper) {
					System.out.flush();
					System.err.println("Assemble");
					System.err.flush();
				}
				runButtonsSetEnabled(true);
				reset();
			}
		});

	}

	@Override
	protected void initializePostGUI() {

		connectButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (connectButton.isConnected()) {
					restoreBackStepper(); // TODO We really need this?
				} else {
					/*
					 * User program should be recompiled (and executed) after
					 * StackVisualizer is launched. This is required for
					 * coherently storing the subroutine call stack.
					 */
					runButtonsSetEnabled(false);
					// Connecting StackVisualizer in the middle of program execution
					// will disable Back Stepper but the button is enabled.
					// Maybe we should disable it by hand or just don't mess with it.
//					marsGui.getRunBackstepAction().isEnabled(); // TODO disable?
					disableBackStepper();
					getStackData();
					updateSpDataRowColIndex();
					table.repaint();

					String msg = "Back Stepping has been disabled.\n"
							+ "Already running programs should be assembled again.";
					showMessageWindow(msg);
				}
			}
		});

		addNewTableRows(INITIAL_ROW_COUNT);
		updateSpDataRowColIndex();
		table.repaint(); // Maybe we can remove this
	}

	/*
	 * getStackData() fires a MemoryAccessNotice every time it reads from memory.
	 * For this reason it should not be called in a code block handling a
	 * MemoryAccessNotice of AccessNotice.READ type as it will lead in infinite
	 * recursive calls of itself.
	 */
	protected void getStackData() {
		int col;

		if (printMemContents)
			System.out.println("getStackData start");
		/*
		 * MARS supports three memory configurations. Only in the default configuration
		 * the initial stack pointer value does NOT point to the highest address of the
		 * stack. Nevertheless, in all three configurations, the initial address pointed
		 * by $sp is a valid address whose contents can be written.
		 *
		 * TODO support visualization of addresses higher than initial $sp value
		 *
		 * Initial value of spAddr is 0x7FFFEFFC = 2147479548 (Default MIPS memory configuration).
		 * The first 4 bytes (1 word) to be displayed are:
		 * 0x7FFFEFFF, 0x7FFFEFFE, 0x7FFFEFFD, 0x7FFFEFFC or in decimal value:
		 * 2147479551, 2147479550, 2147479549, 2147479548.
		 */
		for (int row = 0, addr = maxSpValue; row < numberOfRows; row++) {
			tableModel.setValueAt(formatAddress(addr-3), row, ADDRESS_COLUMN);
			try {
				if (dataPerByte.isSelected()) {
					for (int j = FIRST_BYTE_COLUMN; j <= LAST_BYTE_COLUMN; j++, addr--) {
						int byteValue;
						/*
						 * Endianness determines whether byte position in value and
						 * byte position in memory match.
						 */
						col = (endianness == LITTLE_ENDIAN) ? j : (LAST_BYTE_COLUMN-j) + FIRST_BYTE_COLUMN;
						/*
						 * MARS' check for addresses out of range is performed using word-aligned addresses.
						 * This means that the word on Memory.stackBaseAddress (highest stack address) can
						 * be accessed during word-length data operations but not during half-word or byte-length
						 * operations. This behavior is asymmetrical among the three memory configurations
						 * supported by MARS.
						 */
						if (addr >= Memory.stackBaseAddress && addr <= (Memory.stackBaseAddress+3)) {
							int word = memInstance.getWordNoNotify(alignToCurrentWordBoundary(addr));
							byteValue = (word >> (WORD_LENGTH_BYTES-j)) & 0xffff;
							if (displayDataPerByte)
								System.out.println("(" + row + "," + col + ") - " + addr +": " +
										formatByteLengthMemContents(byteValue));
							tableModel.setValueAt(formatByteLengthMemContents(byteValue), row, col);
						} else {
							byteValue = memInstance.getByte(addr);
							if (displayDataPerByte)
								System.out.println("(" + row + "," + col + ") - " + addr +": " +
										formatByteLengthMemContents(byteValue));
							tableModel.setValueAt(formatByteLengthMemContents(byteValue), row, col);
						}
					}
				} else {
					col = WORD_COLUMN;
					addr -= WORD_LENGTH_BYTES-1; // TODO simplify addr update
					tableModel.setValueAt(formatWordLengthMemContents(memInstance.getWord(addr--)), row, col);
				}
				if (printMemContents) {
					System.out.print(tableModel.getValueAt(row, 0) + ": ");
					if (dataPerByte.isSelected()) {
						for (int i = FIRST_BYTE_COLUMN; i <= LAST_BYTE_COLUMN; i++)
							System.out.print(tableModel.getValueAt(row, i) + (i == LAST_BYTE_COLUMN ? "" : ","));
					} else {
						System.out.print(tableModel.getValueAt(row, WORD_COLUMN));
					}
					System.out.print(" (" + tableModel.getValueAt(row, storedRegisterColumn) + ")");
					System.out.println(" [" + tableModel.getValueAt(row, frameNameColumn) + "]");
				}
			} catch (AddressErrorException aee) {
				aee.printStackTrace();
			}
		}

		if (printMemContents)
			System.out.println("getStackData end\n");
	}

	@Override
	protected void addAsObserver() {
		// To observe stack segment, actual parameters should be
		// reversed due to higher to lower address stack growth.
		addAsObserver(Memory.stackLimitAddress, Memory.stackBaseAddress);
		/*
		 * TODO: verify that Memory.stackBaseAdress+1 to Memory.stackBaseAdress+3
		 * can be observed during half word- or byte-length operations.
		 * Update check in getTableRowIndex() too if needed.
		 */
		addAsObserver(RegisterFile.getRegisters()[SP_REG_NUMBER]);
		addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress);
	}

	@Override
	protected void deleteAsObserver() {
		super.deleteAsObserver(); // Stop observing memory (default)
		deleteAsObserver(RegisterFile.getRegisters()[SP_REG_NUMBER]); // Stop observing $sp
	}

	@Override
	protected void processMIPSUpdate(Observable resource, AccessNotice notice) {

//		System.out.println(notice.accessIsFromMIPS() +" " + notice.accessIsFromGUI() + " " + notice);

		if (!notice.accessIsFromMIPS())
			return;

		disableBackStepper();

		if (notice instanceof MemoryAccessNotice) {
			MemoryAccessNotice m = (MemoryAccessNotice) notice;
			if (Memory.inTextSegment(m.getAddress()))
				processTextMemoryUpdate(m);
			else
				processStackMemoryUpdate(m);
		}
		else if (notice instanceof RegisterAccessNotice) {
			RegisterAccessNotice r = (RegisterAccessNotice) notice;
			processRegisterAccessNotice(r);
		}
	}

	private void processRegisterAccessNotice(RegisterAccessNotice notice) {
		// Currently only $sp is observed
		// TODO: What about observing frame pointer?
		if (notice.getAccessType() == AccessNotice.READ)
			return;

		if (debug)
			System.out.println("\nRegisterAccessNotice (W): " + notice.getRegisterName() + " value: " + getSpValue());

		if (notice.getRegisterName().equals("$sp")) {
			int oldSpDataRowIndex = spDataRowIndex;
			updateSpDataRowColIndex();
			resetStoredRegAndFrameNameColumns(spDataRowIndex + 1, oldSpDataRowIndex);
//			 System.out.println("SP value: " + formatAddress(getSpValue()) + " - tableIndex: " + spDataRowIndex);
			// Add more rows if we are reaching current row count
			if (spDataRowIndex + REMAINING_ROWS_THRESHOLD > numberOfRows) {
				addNewTableRows(5);
			}
			table.repaint(); // Required for coloring $sp position during popping.
		}
	}

	private void processStackMemoryUpdate(MemoryAccessNotice notice) {
		String regName = "", frameName = "";

		if (notice.getAccessType() == AccessNotice.READ)
			return;

		if (regNameToBeStoredInStack != null) {
			regName = regNameToBeStoredInStack;
			regNameToBeStoredInStack = null;
		}

		if (frameNameToBeCreated != null) {
			frameName = frameNameToBeCreated;
			frameNameToBeCreated = null;
		}

		if (debug) {
			System.out.println("\nStackAccessNotice (" +
					((notice.getAccessType() == AccessNotice.READ) ? "R" : "W") + "): "
					+ notice.getAddress() + " value: " + notice.getValue() +
					" (stored: " + regName + ")");
		}

		int row = getTableRowIndex(notice.getAddress());

		if (debug)
			System.out.println("Addr: " + formatAddress(notice.getAddress()) + " - tableIndex: " + row + " (" + regName + ")");

		tableModel.setValueAt(regName, row, storedRegisterColumn);
		tableModel.setValueAt(frameName, row, frameNameColumn);
		getStackData();
		table.repaint();
	}

	private int getTableRowIndex(int memAddress) {
		if (memAddress > Memory.stackBaseAddress || memAddress < Memory.stackLimitAddress) {
			System.err.println("getTableRowIndex() only works for stack segment addresses");
			return -1;
		}
		int rowIndex = (maxSpValueWordAligned - alignToCurrentWordBoundary(memAddress)) / WORD_LENGTH_BYTES;
		if (rowIndex >= numberOfRows) {
			addNewTableRows(rowIndex - numberOfRows + 10);
			table.repaint();
		}
		return rowIndex;
	}

	private void addNewTableRows(int rowNumber) {
		for (int ri = 0; ri < rowNumber; ri++)
			tableModel.addRow(new Object[numberOfColumns]);
		numberOfRows = tableModel.getRowCount();
		getStackData();
	}

	private int getTableColumnIndex(int memAddress) {
		if (memAddress > Memory.stackBaseAddress || memAddress < Memory.stackLimitAddress) {
			System.err.println("getTableColumnIndex() only works for stack segment addresses");
			return -1;
		}
		return LAST_BYTE_COLUMN - (memAddress % WORD_LENGTH_BYTES);
	}

	private void processTextMemoryUpdate(MemoryAccessNotice notice) {
		if (notice.getAccessType() == AccessNotice.WRITE)
			return;

		if (debug) {
			System.out.println("\nTextAccessNotice (R): " + notice.getAddress()
					+ " value: " + notice.getValue() /*+ " = "*/);
		}
//		printBin(notice.getValue());

		try {
			ProgramStatement stmnt =  memInstance.getStatementNoNotify(notice.getAddress());

			/*
			 * The check below is required in case user program is finished running
			 * by dropping of the bottom. This happens when an execution termination
			 * service (Code 10 in $v0) does NOT take place.
			 */
			if (stmnt == null)
				return;

			Instruction instr = stmnt.getInstruction();
			String instrName = instr.getName();
			int[] operands;

			if (isStoreInstruction(instrName)) {
				if (debug)
					System.out.println("Statement TBE: " + stmnt.getPrintableBasicAssemblyStatement());
				operands = stmnt.getOperands();
//				for (int i = 0; i < operands.length; i++)
//					System.out.print(operands[i] + " ");
//				System.out.println();
				regNameToBeStoredInStack = RegisterFile.getRegisters()[operands[I_RS_OPERAND_LIST_INDEX]].getName();
			}
			else if (isJumpInstruction(instrName) || isJumpAndLinkInstruction(instrName)) {
				int targetAdrress = stmnt.getOperand(J_ADDR_OPERAND_LIST_INDEX) * WORD_LENGTH_BYTES;
				String targetLabel = addrToTextSymbol(targetAdrress);
				if (isJumpAndLinkInstruction(instrName)) {
					ras.add(stmnt.getAddress());
					frameNameToBeCreated = targetLabel;
					Integer count = activeFunctionCallStats.addCall(targetLabel);
					frameNameToBeCreated += " (" + count + ")";
				}
				if (targetLabel != null) {
					if (debug) {
						System.out.print("Jumping to: " + targetLabel);
						if (isJumpAndLinkInstruction(instrName))
							System.out.println(" (" + (ras.size()) + ")");
						else
							System.out.println("");
					}
				}
			}
			else if (isJumpRegInstruction(instrName)) {
				int targetRegister = stmnt.getOperand(R_RS_OPERAND_LIST_INDEX);
				// TODO: check $ra only (?)
				Register reg = RegisterFile.getRegisters()[targetRegister];
				int returnAddress = reg.getValue();
				// returnAddress-4 is needed as PC+4 is stored in $ra when jal is executed.
				int jalStatementAddress = returnAddress - WORD_LENGTH_BYTES;
				ProgramStatement jalStatement =  memInstance.getStatementNoNotify(jalStatementAddress);
				int jalTargetAddress = jalStatement.getOperand(J_ADDR_OPERAND_LIST_INDEX) * WORD_LENGTH_BYTES;
				String exitingSubroutineName = addrToTextSymbol(jalTargetAddress);
				activeFunctionCallStats.removeCall(exitingSubroutineName);
				if (debug) {
					System.out.println("Returning from: " + exitingSubroutineName + " (" +ras.size() +
							") to line: " + jalStatement.getSourceLine());
				}

				try {
					Integer rasTopAddress = ras.remove(ras.size()-1);
					if (rasTopAddress.compareTo(jalStatementAddress) != 0) {
						System.out.println("Mismatching return address: " + rasTopAddress + " - " + jalStatementAddress);
					}
				} catch (IndexOutOfBoundsException e) {
					// Exception is thrown whenever function calling instructions are back-stepped (undone)
					// and again executed. Undoing the last step is not supported!
					//
					// It also happens in case StackVisualizer gets disconnected while user program is executing
					// and then is again connected. TODO fix
					//
					// It also happens when tool's Reset button is pressed while user program is executing.

					// e.printStackTrace();
				}
			}
		} catch (AddressErrorException e) {
			// Suppress such warnings
//			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private boolean isStoreInstruction(String instrName) {
		if (instrName.equals("sw") || instrName.equals("sh") ||
				instrName.equals("sc") || instrName.equals("sb"))
			return true;
		return false;
	}

	private boolean isJumpInstruction(String instrName) {
		return (instrName.equals("j"));
	}

	private boolean isJumpAndLinkInstruction(String instrName) {
		return (instrName.equals("jal"));
	}

	private boolean isJumpRegInstruction(String instrName) {
		return (instrName.equals("jr"));
	}

	private String addrToTextSymbol(int address) {
		String addrStr = String.valueOf(address);
		SymbolTable localSymTable = Globals.program.getLocalSymbolTable();
		Symbol symbol = localSymTable.getSymbolGivenAddressLocalOrGlobal(addrStr);
		if (symbol != null) {
			// System.out.println("Symbol: " + symbol.getName());
			return symbol.getName();
		}
		System.err.println("addrToTextSymbol(): Error translating address to label");
		return null;
	}

	private int getSpValue() {
		return RegisterFile.getValue(SP_REG_NUMBER);
	}

	/*
	 * The ignoreObserving flag is required for disabling
	 * BackStepper when the Connect button is pressed.
	 * (The tool is not yet registered as observing)
	 */
	private void disableBackStepper() {
		if (Globals.program == null)
			return;
		BackStepper bs = Globals.program.getBackStepper();
		if (bs == null)
			return;
		if (bs.enabled()) {
			if (debugBackStepper)
				System.err.println("Disabled BackStepper");
			bs.setEnabled(false);
			disabledBackStep = true;
		}
	}

	private void restoreBackStepper() {
		if (disabledBackStep) {
			disabledBackStep = false;
			if (Globals.program == null)
				return;
			BackStepper bs = Globals.program.getBackStepper();
			if (bs == null)
				return;
			if (!bs.enabled()) {
				if (debugBackStepper)
					System.err.println("Enabled BackStepper");
				bs.setEnabled(true);
			}
		}
	}

	private void runButtonsSetEnabled(boolean state) {
		marsGui.getRunGoAction().setEnabled(state);
		marsGui.getRunStepAction().setEnabled(state);
	}

	/**
	 * Utility method to align given address to current full word boundary,
	 * if not already aligned.
	 *
	 * @param address
	 *            a memory address (any int value is potentially valid)
	 * @return address aligned to current word boundary (divisible by 4)
	 */
	private int alignToCurrentWordBoundary(int address) {
		if (Memory.wordAligned(address))
			return address;
		return (Memory.alignToWordBoundary(address) - WORD_LENGTH_BYTES);
	}

	private String formatAddress(int address) {
		if (hexAddressesCheckBox.isSelected())
			return Binary.intToHexString(address);
		else
			return Integer.toString(address);
	}

	private String formatWordLengthMemContents(int data) {
		if (hexValuesCheckBox.isSelected())
			return intTo8DigitHexStringNoPrefix(data);
		else
			return Integer.toString(data);
	}

	private String intTo8DigitHexStringNoPrefix(int data) {
		String leadingZero = new String("0");
		String ret = Integer.toHexString(data);
		while (ret.length() < 8)
			ret = leadingZero.concat(ret);
		return ret;
	}

	private String formatByteLengthMemContents(int data) {
		if (hexValuesCheckBox.isSelected())
			return intTo2DigitHexStringNoPrefix(data);
		else
			return Integer.toString(data);
	}

	private String intTo2DigitHexStringNoPrefix(int data) {
		String leadingZero = new String("0");
		String ret = Integer.toHexString(data);
		while (ret.length() < 2)
			ret = leadingZero.concat(ret);
		return ret;
	}

	@SuppressWarnings("unused")
	private void printBin(int num) {
		int count = 0;
		for (int i = count = 0; i < WORD_LENGTH_BITS; i++, num <<= 1)
			System.out.print((((num & (1 << WORD_LENGTH_BITS)) != 0) ? "1" : "0") +
					((++count % 4 == 0) ? " " : ""));
		System.out.print("\n");
	}

	@Override
	protected void reset() {
		if (debugBackStepper) {
			System.out.flush();
			System.err.println("ResetAction");
			System.err.flush();
		}
		ras.clear();
		activeFunctionCallStats.reset();
		resetStoredRegAndFrameNameColumns(0, numberOfRows-1);
		getStackData();
		updateSpDataRowColIndex();
		table.repaint();
	}

	private void updateSpDataRowColIndex() {
		int spValue = getSpValue();
		spDataRowIndex = getTableRowIndex(spValue);
		spDataColumnIndex = getTableColumnIndex(spValue);
	}

	private void resetStoredRegAndFrameNameColumns(int startRow, int endRow) {
		for (int row = startRow; row <= endRow; row++) {
			tableModel.setValueAt("", row, storedRegisterColumn);
			tableModel.setValueAt("", row, frameNameColumn);
		}
	}

	@Override
	protected JComponent getHelpComponent() {
		// TODO write a proper help component
		final String helpContent = "Stack Visualizer\n\n"
				+ "Release: " + versionID + "   (" + releaseDate + ")\n\n"
				+ "Developed by George Z. Zachos (gzachos@cse.uoi.gr) and\n"
				+ "Petros Manousis (pmanousi@cs.uoi.gr) under the supervision of\n"
				+ "Aristides (Aris) Efthymiou (efthym@cse.uoi.gr).\n\n"
				+ "About\n"
				+ "This tool allows the user to view in real time the memory modification operations\n"
				+ "taking place in the stack segment. The user can also observe how the stack grows.\n"
				+ "The address pointed by the stack pointer is displayed in an orange background\n"
				+ "while the whole word-length data in a yellow one. Lower addresses have a grey\n"
				+ "background (given that stack growth takes place form higher to lower addresses).\n"
				+ "The names of the registers whose contents are stored (sw, sh, sb etc.) in the\n"
				+ "stack, are shown in the \"Stored Reg\" column. In the \"Call Layout\" column, the subroutine\n"
				+ "frame (activation record) layout is displayed, with subroutine names placed on the highest\n"
				+ "address of the corresponding frames.\n\n"
				+ "Note\n"
				+ "This program is suposed to be used as a MARS Tool and NOT as a stand-alone application."
				+ "\n\n"
				+ "Contact\n"
				+ "For questions or comments contact: George Z. Zachos (gzachos@cse.uoi.gr) or\n"
				+ "Aristides Efthymiou (efthym@cse.uoi.gr)";
		JButton help = new JButton("Help");
		help.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				showMessageWindow(helpContent);
			}
		});
		return help;
	}

	private void showMessageWindow(String message) {
		JOptionPane.showMessageDialog(theWindow, message);
	}

	// Class definition
	private static class ActiveFunctionCallStats {
		private HashMap<String, Integer> activeCalls;

		public ActiveFunctionCallStats() {
			activeCalls = new HashMap<>(0);
		}

		public Integer addCall(String subroutineName) {
			Integer newValue;
			if (activeCalls.containsKey(subroutineName)) {
				newValue = activeCalls.get(subroutineName) + 1;
				activeCalls.replace(subroutineName, newValue);
			} else {
				activeCalls.put(subroutineName, 1);
				newValue = 1;
			}
			return newValue;
		}

		public void removeCall(String subroutineName) {
			Integer oldValue = activeCalls.get(subroutineName);
			if (oldValue == null)
				System.err.println("ActiveFunctionCallStats.removeCall: " + subroutineName + " doesn't exist");
			else
				activeCalls.replace(subroutineName, oldValue - 1);
		}

		public void reset() {
			activeCalls.clear();
		}
	}

}
